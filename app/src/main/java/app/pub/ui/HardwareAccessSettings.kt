package app.pub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pub.HostModel
import app.pub.core.PermissionPolicy.Rule
import app.pub.core.PhoneFilesContract
import app.pub.hardware.HardwareInventory

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardwareAccessSettings(host:HostModel,onAndroidSettings:()->Unit) {
    var cap by rememberSaveable {mutableStateOf("microphone")}
    var capsOpen by remember {mutableStateOf(false)}
    var projectsOpen by remember {mutableStateOf(false)}
    var projectId by rememberSaveable {mutableStateOf("")}
    val groups=linkedMapOf("microphone" to "Microphone") + HardwareInventory.labels
    val choices=host.projects.map {it.id to it.bundle.name}
    val selected=choices.find {it.first==projectId} ?: choices.firstOrNull()
    val mic=cap=="microphone"
    val block=if(mic) host.globalBlocked else host.hardwareBlocked(cap)
    val default=if(mic) host.globalDefault else host.hardwareDefault(cap)
    Text("Access",style=MaterialTheme.typography.titleLarge)
    Box {
        OutlinedButton(onClick={capsOpen=true},modifier=Modifier.fillMaxWidth().testTag("capability-picker")){Text(groups[cap] ?: cap)}
        DropdownMenu(expanded=capsOpen,onDismissRequest={capsOpen=false}) {
            groups.forEach {(key,label)->DropdownMenuItem(text={Text(label)},onClick={cap=key;capsOpen=false})}
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=12.dp)) {
            Text("Block ${groups[cap]?.lowercase()} everywhere",style=MaterialTheme.typography.titleMedium)
            if(mic) Text("Includes voice input for building apps",style=MaterialTheme.typography.bodySmall)
        }
        Switch(checked=block,onCheckedChange={if(mic) host.blockGlobally(it) else host.changeHardwareBlock(cap,it)},modifier=Modifier.testTag(if(mic) "global-mic-block" else "global-$cap-block"))
    }
    Text("Default access",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(Rule.ASK to "Ask",Rule.ALLOW to "Allow while open").forEach {(rule,label)->
            FilterChip(selected=default==rule,onClick={if(mic) host.changeGlobalDefault(rule) else host.changeHardwareDefault(cap,rule)},label={Text(label)})
        }
    }
    Text("Per-app access",style=MaterialTheme.typography.titleMedium)
    if(selected==null) Text("No saved apps yet",style=MaterialTheme.typography.bodyMedium)
    else {
        val own=if(mic) host.microphoneRuleFor(selected.first) else host.hardwareRule(selected.first,cap)
        Box {
            OutlinedButton(onClick={projectsOpen=true},modifier=Modifier.fillMaxWidth().testTag("project-permission-picker")){Text(selected.second)}
            DropdownMenu(expanded=projectsOpen,onDismissRequest={projectsOpen=false}) {choices.forEach {(id,name)->DropdownMenuItem(text={Text(name)},onClick={projectId=id;projectsOpen=false})}}
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(Rule.INHERIT to "Use default",Rule.ASK to "Ask",Rule.ALLOW to "Allow",Rule.DENY to "Deny").forEach {(rule,label)->
                FilterChip(selected=own==rule,onClick={if(mic) host.changeProjectRule(selected.first,rule) else host.changeHardwareRule(selected.first,cap,rule)},label={Text(label)})
            }
        }
        if(cap==PhoneFilesContract.CAPABILITY) FileAccessForApp(host,selected.first)
    }
    if(cap==PhoneFilesContract.CAPABILITY) Text("Apps see only the files and folders you pick in Android's file picker, by name, size and type. A rule controls whether an app may use them; Forget file access removes what it was given. Your files are never deleted with an app.",style=MaterialTheme.typography.bodySmall)
    Text("Apps must declare the capability. Android permissions and device selection still apply. Changing access stops active hardware sessions.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick=onAndroidSettings){Text("Android app permissions")}
}

/** How many files and folders the saved app [id] was given, and Forget file access, which releases them all. */
@Composable
private fun FileAccessForApp(host:HostModel,id:String) {
    val count by produceState<Int?>(null,id,host.fileGrantsRevision) { value=runCatching { host.fileGrantCount(id) }.getOrNull() }
    Text(when(count) {
        null -> "Checking file access…"
        0 -> "No files or folders picked for this app"
        1 -> "Has access to 1 file or folder you picked"
        else -> "Has access to $count files and folders you picked"
    },style=MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick={host.forgetFileAccess(id)},enabled=(count ?: 0)>0,modifier=Modifier.testTag("forget-file-access")){Text("Forget file access")}
}
