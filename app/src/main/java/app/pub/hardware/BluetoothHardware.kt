package app.pub.hardware

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-selected Bluetooth data access, not headset/audio-profile control.
 * The broker must check `bluetooth`, CONNECT/SCAN, user grant and foreground before handle().
 * No address or device ID from JavaScript is accepted. `choose` is the only selection entrypoint.
 *
 * Operations/args:
 * choose {scanMs?:1000..10000, bondedOnly?:boolean}; connect {}; disconnect {};
 * read {service:UUID, characteristic:UUID};
 * write {service:UUID, characteristic:UUID, data:[0..255](1..512), withResponse?:boolean};
 * subscribe {service:UUID, characteristic:UUID, enabled?:boolean};
 * classicConnect {service:UUID}; classicWrite {data:[0..255](1..4096)};
 * classicRead {maxBytes?:1..4096, timeoutMs?:1000..10000}.
 * UUIDs may be canonical or 4/8 hex digit Bluetooth shorthand. `data` is bytes, not base64.
 *
 * GATT operations are serialized; concurrent operations return busy instead of being queued.
 * Connect/MTU negotiation/service discovery: 15s total. GATT read/write/subscribe: 10s. RFCOMM connect/write:15s.
 * The `services` event reports the negotiated `mtu`; writes without response are limited to mtu - 3 bytes.
 * RFCOMM read timeout closes that socket to interrupt its blocking read; reconnect afterwards.
 * BLE notifications are rate-limited to at most 10 events/second per characteristic; within a burst,
 * intermediate values are dropped but the latest value is always delivered.
 * Events: status, device, services, value, result, error, disconnected, cancelled.
 * Every value event includes a bounded `bytes` array; addresses never leave this adapter.
 * `close()` is terminal: immediately suppresses callbacks, then stops scans/connections/workers.
 */
@SuppressLint("MissingPermission") // Native broker enforces runtime Android and per-project grants.
class BluetoothHardware(
    context: Context,
    private val chooseDevice: (List<DeviceChoice>, (String?) -> Unit) -> Unit
) {
    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val thread = HandlerThread("PUB Bluetooth").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val closed = AtomicBoolean(false)
    private var connectionEpoch = 0L
    @Volatile private var chooserEpoch = 0L
    private var chooserActive = false
    private var chooserEmit: Emitter? = null
    private var scanning: ScanCallback? = null
    private var scanDeadline: Runnable? = null
    private var chooserDeadline: Runnable? = null
    private var selected: Candidate? = null
    private var gatt: BluetoothGatt? = null
    private var servicesReady = false
    private var socket: BluetoothSocket? = null
    private var connectionEmit: Emitter? = null
    private var pending: Pending? = null
    private val subscriptions = mutableMapOf<String, Emitter>()
    private val lastNotification = mutableMapOf<String, Long>()
    private val trailingNotification = mutableMapOf<String, Runnable>()
    private var attMtu = DEFAULT_MTU
    private var discoveryStarted = false

    private data class Candidate(val id: String, val device: BluetoothDevice, val name: String, val paired: Boolean)
    private data class Pending(
        val operation: String,
        val emit: Emitter,
        val key: String? = null,
        val enabled: Boolean? = null,
        var deadline: Runnable? = null,
        var task: Future<*>? = null
    )
    private class InputError(message: String) : IllegalArgumentException(message)

    fun inventory(): JsonObject = obj {
        addProperty("available", adapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
        addProperty("ble", context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        runCatching { adapter?.isEnabled }.getOrNull()?.let { addProperty("enabled", it) }
        add("protocols", JsonArray().apply { add("gatt"); add("rfcomm") })
        addProperty("audioProfileControl", false)
        addProperty("requiresDeviceSelection", true)
    }

    fun handle(operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit) {
        if (closed.get()) return
        // Snapshot caller-owned JSON before handing it to another thread.
        val input = args.deepCopy()
        worker.post {
            if (closed.get()) return@post
            try {
                val bluetooth = adapter ?: throw InputError("Bluetooth is not available on this phone.")
                if (operation != "disconnect" && !bluetooth.isEnabled) throw InputError("Turn on Bluetooth in Android settings first.")
                if (operation !in setOf("disconnect", "choose") && chooserActive) throw InputError("Finish choosing a device first.")
                when (operation) {
                    "choose" -> choose(input, emit)
                    "connect" -> connect(emit)
                    "read" -> read(input, emit)
                    "write" -> write(input, emit)
                    "subscribe" -> subscribe(input, emit)
                    "disconnect" -> {
                        cancelChooser()
                        finishPendingError("cancelled", "Operation cancelled by disconnect.")
                        resetConnection()
                        send(emit, "disconnected", obj { addProperty("reason", "requested") })
                    }
                    "classicConnect" -> classicConnect(input, emit)
                    "classicRead" -> classicRead(input, emit)
                    "classicWrite" -> classicWrite(input, emit)
                    else -> throw InputError("Unsupported Bluetooth operation. Audio routing uses the audio capability, not GATT or RFCOMM.")
                }
            } catch (e: InputError) {
                fail(emit, operation, "invalid_request", e.message ?: "Invalid Bluetooth request.")
            } catch (_: SecurityException) {
                val hadPending = pending != null
                finishPendingError("permission", "Bluetooth permission was revoked.")
                // A failing choose gets its error below; an earlier open chooser is answered as cancelled.
                cancelChooser(notify = operation != "choose")
                resetConnection()
                if (!hadPending) fail(emit, operation, "permission", "Bluetooth permission was revoked. Review Settings → Access.")
            } catch (_: Exception) {
                // Platform exceptions sometimes contain the hardware MAC; never forward their messages.
                val hadPending = pending != null
                finishPendingError("unavailable", "Bluetooth could not complete this operation.")
                cancelChooser(notify = operation != "choose")
                resetConnection()
                if (!hadPending) fail(emit, operation, "unavailable", "Bluetooth could not complete this operation. Check the selected device and its supported protocol.")
            }
        }
    }

    private fun choose(args: JsonObject, emit: Emitter) {
        if (chooserActive) throw InputError("A device chooser is already open.")
        if (pending != null) throw InputError("A Bluetooth operation is already running.")
        val bondedOnly = bool(args, "bondedOnly", false)
        val scanMs = integer(args, "scanMs", 10_000, 1000, 10_000).toLong()
        val candidates = linkedMapOf<String, Candidate>() // Hardware addresses remain private map keys.
        adapter!!.bondedDevices.take(MAX_CHOICES).forEach { device ->
            candidates[device.address] = Candidate(UUID.randomUUID().toString(), device, safeName(device.name), true)
        }
        resetConnection()
        selected = null
        chooserActive = true
        chooserEmit = emit
        val epoch = ++chooserEpoch
        fun present() {
            if (closed.get() || chooserEpoch != epoch || !chooserActive) return
            stopScan()
            if (candidates.isEmpty()) {
                chooserActive = false
                fail(emit, "choose", "no_devices", "No Bluetooth devices found. Pair the device in Android settings or make its BLE service discoverable.")
                return
            }
            val choices = candidates.values.toList()
            chooserDeadline = Runnable {
                if (chooserEpoch == epoch && chooserActive) {
                    chooserActive = false; chooserEpoch++
                    fail(emit, "choose", "timeout", "Device selection timed out. Choose again.")
                }
            }.also { worker.postDelayed(it, 60_000) }
            main.post {
                if (closed.get() || chooserEpoch != epoch) return@post
                try {
                    chooseDevice(choices.map { DeviceChoice(it.id, it.name + if (it.paired) " · paired" else " · nearby") }) { chosenId ->
                        worker.post selection@{
                            if (closed.get() || chooserEpoch != epoch || !chooserActive) return@selection
                            chooserDeadline?.let(worker::removeCallbacks); chooserDeadline = null
                            chooserActive = false
                            if (chosenId == null) {
                                send(emit, "cancelled", obj { addProperty("operation", "choose") })
                                return@selection
                            }
                            val chosen = choices.firstOrNull { it.id == chosenId }
                            if (chosen == null) {
                                fail(emit, "choose", "invalid_selection", "The selected Bluetooth device is no longer available.")
                                return@selection
                            }
                            selected = chosen
                            send(emit, "device", deviceJson(chosen))
                        }
                    }
                } catch (_: Exception) {
                    worker.post {
                        if (chooserEpoch == epoch) { cancelChooser(notify = false); fail(emit, "choose", "unavailable", "Could not open the Bluetooth device chooser.") }
                    }
                }
            }
        }
        if (bondedOnly || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) { present(); return }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { present(); return }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.take(MAX_CHOICES).forEach(::record) }
            private fun record(result: ScanResult) {
                worker.post {
                    if (closed.get() || chooserEpoch != epoch || scanning !== this) return@post
                    runCatching {
                        val device = result.device
                        if (candidates.size < MAX_CHOICES && !candidates.containsKey(device.address)) {
                            candidates[device.address] = Candidate(UUID.randomUUID().toString(), device,
                                safeName(device.name ?: result.scanRecord?.deviceName), device.bondState == BluetoothDevice.BOND_BONDED)
                        }
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                worker.post {
                    if (chooserEpoch == epoch && scanning === this && !closed.get()) {
                        send(emit, "status", obj { addProperty("message", "BLE discovery was unavailable; showing paired devices."); addProperty("scanError", errorCode) })
                        present()
                    }
                }
            }
        }
        scanning = callback
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)
            send(emit, "status", obj { addProperty("message", "Looking for Bluetooth devices…"); addProperty("scanMs", scanMs) })
            scanDeadline = Runnable { present() }.also { worker.postDelayed(it, scanMs) }
        } catch (_: Exception) {
            stopScan()
            if (candidates.isNotEmpty()) present()
            else { chooserActive = false; fail(emit, "choose", "scan_unavailable", "Bluetooth discovery could not start. Review Nearby devices permission and Bluetooth settings.") }
        }
    }

    private fun stopScan() {
        scanDeadline?.let(worker::removeCallbacks); scanDeadline = null
        val callback = scanning; scanning = null
        if (callback != null) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    /**
     * Invalidates any open chooser. The broker keeps its hardware-screen lock (and the picker dialog)
     * until the choose request is answered, so an active chooser is answered as cancelled unless the
     * caller is about to report its own error for that request.
     */
    private fun cancelChooser(notify: Boolean = true) {
        val waiting = chooserEmit.takeIf { chooserActive && notify }
        chooserEpoch++; chooserActive = false; chooserEmit = null
        chooserDeadline?.let(worker::removeCallbacks); chooserDeadline = null
        stopScan()
        waiting?.let { send(it, "cancelled", obj { addProperty("operation", "choose") }) }
    }

    private fun connect(emit: Emitter) {
        ensureIdle()
        val chosen = selected ?: throw InputError("Choose a Bluetooth device first.")
        if (chosen.device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) throw InputError("The selected device is Bluetooth Classic. Use its documented RFCOMM service or the audio capability for headset routing.")
        resetConnection()
        connectionEmit = emit
        begin("connect", emit, 15_000)
        val epoch = connectionEpoch
        val callback = object : BluetoothGattCallback() {
            private fun current(client: BluetoothGatt) = !closed.get() && connectionEpoch == epoch && gatt === client
            override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
                if (!current(client)) { runCatching { client.close() }; return }
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        finishPendingError("gatt_$status", "The device rejected or lost the Bluetooth connection.")
                        disconnected("connection_error")
                    }
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        // Without a larger ATT MTU, writes are limited to 20 bytes. Discovery follows
                        // onMtuChanged; peripherals that never answer the exchange fall back after 5s.
                        if (runCatching { client.requestMtu(REQUESTED_MTU) }.getOrDefault(false)) {
                            worker.postDelayed({ if (current(client)) discover(client) }, MTU_FALLBACK_MS)
                        } else discover(client)
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        finishPendingError("disconnected", "The Bluetooth device disconnected.")
                        disconnected("remote")
                    }
                }
            }
            override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
                if (!current(client)) return
                if (status == BluetoothGatt.GATT_SUCCESS) attMtu = mtu.coerceIn(DEFAULT_MTU, REQUESTED_MTU)
                discover(client) // No-op once discovery started (the peripheral may also renegotiate later).
            }
            override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
                if (!current(client)) return
                val op = pending?.takeIf { it.operation == "connect" } ?: return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishPendingError("gatt_$status", "GATT service discovery failed. This device may require a different protocol.")
                    disconnected("discovery_failed")
                    return
                }
                clearPending(); servicesReady = true
                send(op.emit, "services", servicesJson(client))
            }
            override fun onCharacteristicRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (current(client)) readResult(characteristic, value, status)
            }
            @Deprecated("Android 12 callback")
            override fun onCharacteristicRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33 && current(client)) readResult(characteristic, characteristic.value?.copyOf() ?: byteArrayOf(), status)
            }
            override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (!current(client)) return
                val op = pending?.takeIf { it.operation == "write" && it.key == key(characteristic) } ?: return
                clearPending()
                if (status == BluetoothGatt.GATT_SUCCESS) result(op.emit, "write")
                else fail(op.emit, "write", "gatt_$status", "The characteristic write failed.")
            }
            override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (!current(client) || descriptor.uuid != CCCD) return
                val characteristic = descriptor.characteristic
                val op = pending?.takeIf { it.operation == "subscribe" && it.key == key(characteristic) } ?: return
                clearPending()
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (op.enabled == true) subscriptions[op.key!!] = op.emit else subscriptions.remove(op.key)
                    send(op.emit, "result", obj { addProperty("operation", "subscribe"); addProperty("enabled", op.enabled) })
                } else {
                    runCatching { client.setCharacteristicNotification(characteristic, false) }
                    subscriptions.remove(op.key)
                    fail(op.emit, "subscribe", "gatt_$status", "The device rejected notifications or indications.")
                }
            }
            override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (current(client)) notification(characteristic, value)
            }
            @Deprecated("Android 12 callback")
            override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33 && current(client)) notification(characteristic, characteristic.value?.copyOf() ?: byteArrayOf())
            }
        }
        gatt = chosen.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, worker)
        if (gatt == null) { finishPendingError("connect_failed", "Could not open a GATT connection."); resetConnection() }
    }

    /** Starts GATT service discovery once per connection, after MTU negotiation. Runs on [worker]. */
    private fun discover(client: BluetoothGatt) {
        if (discoveryStarted || pending?.operation != "connect") return
        discoveryStarted = true
        if (!runCatching { client.discoverServices() }.getOrDefault(false)) {
            finishPendingError("discovery_failed", "Could not discover the device's GATT services.")
            disconnected("discovery_failed")
        }
    }

    private fun read(args: JsonObject, emit: Emitter) {
        ensureIdle()
        val characteristic = characteristic(args)
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) throw InputError("This characteristic is not readable.")
        begin("read", emit, 10_000, key(characteristic))
        if (!gatt!!.readCharacteristic(characteristic)) finishPendingError("read_rejected", "The Bluetooth stack could not start this read.")
    }

    private fun readResult(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        val op = pending?.takeIf { it.operation == "read" && it.key == key(characteristic) } ?: return
        clearPending()
        if (status == BluetoothGatt.GATT_SUCCESS) send(op.emit, "value", valueJson(value.take(MAX_GATT_BYTES).toByteArray(), characteristic, false, value.size > MAX_GATT_BYTES))
        else fail(op.emit, "read", "gatt_$status", "The characteristic read failed.")
    }

    private fun write(args: JsonObject, emit: Emitter) {
        ensureIdle()
        val characteristic = characteristic(args)
        val value = bytes(args, MAX_GATT_BYTES)
        val withResponse = bool(args, "withResponse", true)
        val property = if (withResponse) BluetoothGattCharacteristic.PROPERTY_WRITE else BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        if (characteristic.properties and property == 0) throw InputError("This characteristic does not support the requested write mode.")
        // A write without response must fit one ATT packet; Android would otherwise truncate it silently.
        if (!withResponse && value.size > attMtu - 3) throw InputError("A write without response can carry at most ${attMtu - 3} bytes on this connection.")
        val mode = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        begin("write", emit, 10_000, key(characteristic))
        val accepted = if (Build.VERSION.SDK_INT >= 33) gatt!!.writeCharacteristic(characteristic, value, mode) == BluetoothStatusCodes.SUCCESS
        else { characteristic.writeType = mode; characteristic.value = value; gatt!!.writeCharacteristic(characteristic) }
        if (!accepted) finishPendingError("write_rejected", "The Bluetooth stack could not start this write.")
    }

    private fun subscribe(args: JsonObject, emit: Emitter) {
        ensureIdle()
        val characteristic = characteristic(args)
        val enabled = bool(args, "enabled", true)
        val notify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!notify && !indicate) throw InputError("This characteristic does not support notifications or indications.")
        val descriptor = characteristic.getDescriptor(CCCD) ?: throw InputError("This characteristic has no standard notification configuration descriptor.")
        if (!gatt!!.setCharacteristicNotification(characteristic, enabled)) throw InputError("Local Bluetooth notifications could not be configured.")
        val value = if (!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            else if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        begin("subscribe", emit, 10_000, key(characteristic), enabled)
        val accepted = if (Build.VERSION.SDK_INT >= 33) gatt!!.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        else { descriptor.value = value; gatt!!.writeDescriptor(descriptor) }
        if (!accepted) {
            runCatching { gatt?.setCharacteristicNotification(characteristic, false) }
            subscriptions.remove(key(characteristic))
            finishPendingError("subscribe_rejected", "The Bluetooth stack could not configure device notifications.")
        }
    }

    /**
     * Paces each characteristic separately, so one chatty characteristic cannot starve the others.
     * Values arriving inside the interval replace each other, and the newest is delivered when the
     * interval ends, so the last value of a burst is never lost. Runs on [worker].
     */
    private fun notification(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        val key = key(characteristic)
        val emit = subscriptions[key] ?: return
        val value = valueJson(bytes.take(MAX_GATT_BYTES).toByteArray(), characteristic, true, bytes.size > MAX_GATT_BYTES)
        val epoch = connectionEpoch
        val deliver = Runnable {
            trailingNotification.remove(key)
            if (closed.get() || connectionEpoch != epoch || subscriptions[key] !== emit) return@Runnable
            lastNotification[key] = SystemClock.elapsedRealtime()
            send(emit, "value", value)
        }
        trailingNotification.remove(key)?.let(worker::removeCallbacks)
        val wait = NOTIFICATION_INTERVAL_MS - (SystemClock.elapsedRealtime() - (lastNotification[key] ?: Long.MIN_VALUE / 2))
        if (wait <= 0) deliver.run()
        else { trailingNotification[key] = deliver; worker.postDelayed(deliver, wait) }
    }

    private fun characteristic(args: JsonObject): BluetoothGattCharacteristic {
        val client = gatt?.takeIf { servicesReady } ?: throw InputError("Connect to a GATT device and wait for its services first.")
        val serviceId = uuid(args, "service"); val characteristicId = uuid(args, "characteristic")
        val services = client.services.filter { it.uuid == serviceId }
        if (services.size != 1) throw InputError("The requested service is missing or ambiguous on this device.")
        val matches = services.single().characteristics.filter { it.uuid == characteristicId }
        if (matches.size != 1) throw InputError("The requested characteristic is missing or ambiguous on this device.")
        return matches.single()
    }

    private fun classicConnect(args: JsonObject, emit: Emitter) {
        ensureIdle()
        val chosen = selected ?: throw InputError("Choose a paired Bluetooth device first.")
        if (chosen.device.bondState != BluetoothDevice.BOND_BONDED) throw InputError("Pair this device in Android Bluetooth settings before opening its RFCOMM service.")
        val service = uuid(args, "service")
        resetConnection(); connectionEmit = emit
        val client = chosen.device.createRfcommSocketToServiceRecord(service)
        socket = client
        val op = begin("classicConnect", emit, 15_000)
        val epoch = connectionEpoch
        op.task = io.submit {
            try {
                client.connect()
                worker.post {
                    if (!currentSocket(client, epoch) || pending !== op) return@post
                    clearPending()
                    send(emit, "result", obj { addProperty("operation", "classicConnect"); addProperty("transport", "classic"); addProperty("service", service.toString()) })
                }
            } catch (_: Exception) {
                worker.post {
                    if (currentSocket(client, epoch) && pending === op) {
                        finishPendingError("rfcomm_connect_failed", "RFCOMM connection failed. This device must expose the requested serial-data service; headset audio is not RFCOMM.")
                        disconnected("connection_error")
                    }
                }
            }
        }
    }

    private fun classicRead(args: JsonObject, emit: Emitter) {
        ensureIdle()
        val client = socket?.takeIf { it.isConnected } ?: throw InputError("Connect the selected device's RFCOMM service first.")
        val maxBytes = integer(args, "maxBytes", 512, 1, 4096)
        val timeout = integer(args, "timeoutMs", 5000, 1000, 10_000).toLong()
        val op = begin("classicRead", emit, timeout)
        val epoch = connectionEpoch
        op.task = io.submit {
            try {
                val data = ByteArray(maxBytes)
                val count = client.inputStream.read(data)
                worker.post {
                    if (!currentSocket(client, epoch) || pending !== op) return@post
                    clearPending()
                    if (count < 0) {
                        fail(emit, "classicRead", "disconnected", "The RFCOMM device closed its data stream.")
                        disconnected("end_of_stream")
                    }
                    else send(emit, "value", valueJson(data.copyOf(count), null, false, false))
                }
            } catch (_: Exception) {
                worker.post {
                    if (currentSocket(client, epoch) && pending === op) {
                        finishPendingError("rfcomm_read_failed", "The RFCOMM connection could not be read.")
                        disconnected("read_error")
                    }
                }
            }
        }
    }

    private fun classicWrite(args: JsonObject, emit: Emitter) {
        ensureIdle()
        val client = socket?.takeIf { it.isConnected } ?: throw InputError("Connect the selected device's RFCOMM service first.")
        val data = bytes(args, 4096)
        val op = begin("classicWrite", emit, 15_000)
        val epoch = connectionEpoch
        op.task = io.submit {
            try {
                client.outputStream.write(data); client.outputStream.flush()
                worker.post {
                    if (!currentSocket(client, epoch) || pending !== op) return@post
                    clearPending(); result(emit, "classicWrite")
                }
            } catch (_: Exception) {
                worker.post {
                    if (currentSocket(client, epoch) && pending === op) {
                        finishPendingError("rfcomm_write_failed", "The RFCOMM write failed.")
                        disconnected("write_error")
                    }
                }
            }
        }
    }

    private fun currentSocket(client: BluetoothSocket, epoch: Long) = !closed.get() && connectionEpoch == epoch && socket === client
    private fun ensureIdle() { if (pending != null) throw InputError("A Bluetooth operation is already running. Wait for its result.") }

    private fun begin(operation: String, emit: Emitter, timeout: Long, key: String? = null, enabled: Boolean? = null): Pending {
        ensureIdle()
        val op = Pending(operation, emit, key, enabled)
        pending = op
        op.deadline = Runnable {
            if (pending === op && !closed.get()) {
                finishPendingError("timeout", "Bluetooth operation timed out. Reconnect before trying again.")
                disconnected("timeout")
            }
        }.also { worker.postDelayed(it, timeout) }
        return op
    }

    private fun clearPending() {
        val op = pending; pending = null
        op?.deadline?.let(worker::removeCallbacks)
        op?.task?.cancel(true)
    }

    private fun finishPendingError(code: String, message: String) {
        val op = pending ?: return
        clearPending(); fail(op.emit, op.operation, code, message)
    }

    private fun disconnected(reason: String) {
        val emit = connectionEmit
        resetConnection()
        if (emit != null) send(emit, "disconnected", obj { addProperty("reason", reason) })
    }

    private fun resetConnection() {
        connectionEpoch++
        clearPending()
        subscriptions.clear(); servicesReady = false; discoveryStarted = false; attMtu = DEFAULT_MTU
        trailingNotification.values.forEach(worker::removeCallbacks); trailingNotification.clear(); lastNotification.clear()
        val oldGatt = gatt; gatt = null
        runCatching { oldGatt?.disconnect() }; runCatching { oldGatt?.close() }
        val oldSocket = socket; socket = null
        // Closing BluetoothSocket aborts in-progress blocking connect/read/write on its IO worker.
        runCatching { oldSocket?.close() }
        connectionEmit = null
    }

    private fun servicesJson(client: BluetoothGatt): JsonObject = obj {
        var remaining = 128
        var truncated = client.services.size > 24
        add("services", JsonArray().apply {
            client.services.take(24).forEach { service -> add(obj {
                addProperty("uuid", service.uuid.toString())
                val characters = service.characteristics.take(remaining)
                if (characters.size < service.characteristics.size) truncated = true
                remaining -= characters.size
                add("characteristics", JsonArray().apply { characters.forEach { characteristic -> add(obj {
                    val properties = characteristic.properties
                    addProperty("uuid", characteristic.uuid.toString()); addProperty("properties", properties)
                    addProperty("readable", properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                    addProperty("writable", properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    addProperty("notifiable", properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                }) } })
            }) }
        })
        addProperty("truncated", truncated)
        addProperty("mtu", attMtu); addProperty("maxWriteWithoutResponse", attMtu - 3)
    }

    private fun deviceJson(candidate: Candidate) = obj {
        addProperty("id", candidate.id); addProperty("name", candidate.name); addProperty("paired", candidate.paired)
        addProperty("transport", when (runCatching { candidate.device.type }.getOrDefault(0)) {
            BluetoothDevice.DEVICE_TYPE_LE -> "ble"; BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"; else -> "unknown"
        })
    }

    private fun valueJson(value: ByteArray, characteristic: BluetoothGattCharacteristic?, notification: Boolean, truncated: Boolean) = obj {
        addProperty("transport", if (characteristic == null) "classic" else "ble")
        if (characteristic != null) {
            addProperty("service", characteristic.service.uuid.toString()); addProperty("characteristic", characteristic.uuid.toString())
        }
        add("bytes", JsonArray().apply { value.forEach { add(it.toInt() and 255) } })
        addProperty("notification", notification); addProperty("truncated", truncated)
    }

    private fun key(characteristic: BluetoothGattCharacteristic) = "${characteristic.service.uuid}/${characteristic.uuid}"
    private fun result(emit: Emitter, operation: String) = send(emit, "result", obj { addProperty("operation", operation) })
    private fun fail(emit: Emitter, operation: String, code: String, message: String) = send(emit, "error", obj {
        addProperty("operation", operation); addProperty("code", code); addProperty("message", message)
    })
    private fun send(emit: Emitter, event: String, data: JsonObject) {
        if (closed.get()) return
        main.post { if (!closed.get()) emit(event, data) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Suppression is synchronous; resource shutdown is serialized off the UI thread.
        main.removeCallbacksAndMessages(null)
        worker.post {
            cancelChooser(); resetConnection(); selected = null
            worker.removeCallbacksAndMessages(null)
            io.shutdownNow(); thread.quitSafely()
        }
    }

    private fun safeName(name: String?): String {
        val clean = name.orEmpty().replace(Regex("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}"), "Bluetooth device")
            .filter { !it.isISOControl() }.trim().take(80)
        return clean.ifBlank { "Bluetooth device" }
    }
    private fun uuid(args: JsonObject, field: String): UUID {
        val value = args[field]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw InputError("A $field UUID is required.")
        val normalized = when {
            value.matches(Regex("[0-9a-fA-F]{4}")) -> "0000$value-0000-1000-8000-00805f9b34fb"
            value.matches(Regex("[0-9a-fA-F]{8}")) -> "$value-0000-1000-8000-00805f9b34fb"
            value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) -> value
            else -> throw InputError("Invalid $field UUID.")
        }
        return UUID.fromString(normalized)
    }
    private fun bool(args: JsonObject, key: String, default: Boolean): Boolean {
        val value = args[key] ?: return default
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) throw InputError("$key must be a boolean.")
        return value.asBoolean
    }
    private fun integer(args: JsonObject, key: String, default: Int, min: Int, max: Int): Int {
        val value = args[key] ?: return default
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw InputError("$key must be an integer.")
        val number = runCatching { value.asBigDecimal.intValueExact() }.getOrNull() ?: throw InputError("$key must be an integer.")
        if (number !in min..max) throw InputError("$key must be between $min and $max.")
        return number
    }
    private fun bytes(args: JsonObject, max: Int): ByteArray {
        val data = args["data"]?.takeIf { it.isJsonArray }?.asJsonArray ?: throw InputError("data must be a byte array.")
        if (data.size() !in 1..max) throw InputError("data must contain between 1 and $max bytes.")
        return ByteArray(data.size()) { index ->
            val value = data[index]
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw InputError("data must contain integers from 0 to 255.")
            val number = runCatching { value.asBigDecimal.intValueExact() }.getOrNull() ?: throw InputError("data must contain integers from 0 to 255.")
            if (number !in 0..255) throw InputError("data must contain integers from 0 to 255.")
            number.toByte()
        }
    }
    private inline fun obj(block: JsonObject.() -> Unit) = JsonObject().apply(block)
    companion object {
        private const val MAX_CHOICES = 48
        private const val MAX_GATT_BYTES = 512
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 517 // 512-byte attribute value + 5 bytes ATT overhead.
        private const val MTU_FALLBACK_MS = 5000L // Leaves 10s of the 15s connect budget for discovery.
        private const val NOTIFICATION_INTERVAL_MS = 100L
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

private typealias Emitter = (String, JsonObject) -> Unit
