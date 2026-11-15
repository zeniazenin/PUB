package app.pub.hardware

import app.pub.core.HardwareContract
import app.pub.core.ProjectBundle
import com.google.gson.*

/** Local, inspectable hardware exercise; follows exactly the same sandbox and permission path as generated apps. */
object HardwareDemo {
    fun bundle():ProjectBundle = ProjectBundle.parse(JsonObject().apply {
        addProperty("name","Hardware check");addProperty("description","Try the native hardware controls available to PUB apps. Device protocols may need a purpose-built app.")
        add("capabilities",JsonArray().apply {HardwareContract.CAPABILITIES.sorted().forEach {add(it)}})
        addProperty("source",SOURCE)
    }.toString())
    private val SOURCE = """
        var page='menu',message='Choose a hardware feature.',photo=null;
        function text(t){return {type:'text',text:String(t).slice(0,512)}}
        function button(label,action){return {type:'button',label:label,action:action}}
        function dispatch(raw){
          var e=JSON.parse(raw),effects=[];
          function hw(cap,op,args){effects.push({type:'hardware.request',id:cap+'_'+op,capability:cap,operation:op,args:args||{}})}
          if(e.type==='init'){photo=e.storage.photo||null;}
          if(e.type==='action'){
            var a=e.action;
            if(a==='menu'){page='menu';message='Choose a hardware feature.';}
            else if(a.indexOf('page:')===0){page=a.slice(5);message='Choose a test below.';}
            else if(a==='camera')hw('camera','capture');
            else if(a==='audioRoute')hw('audio','route');
            else if(a==='audioTone')hw('audio','tone',{tone:'digit5',volume:30,durationMs:1500});
            else if(a==='audioSpeak')hw('audio','speak',{text:'PUB headphone audio test. Your selected audio device is working.'});
            else if(a==='audioReset')hw('audio','routeReset');
            else if(a==='audioStop')hw('audio','stop');
            else if(a==='microphone')effects.push({type:'microphone.start'});
            else if(a==='microphoneStop')effects.push({type:'microphone.stop'});
            else if(a==='sensor')hw('sensors','start',{sensor:'accelerometer',rateHz:5,durationMs:60000});
            else if(a==='sensorList')hw('sensors','list');
            else if(a==='sensorStop')hw('sensors','stop');
            else if(a==='location')hw('location','current',{timeoutMs:15000});
            else if(a==='bluetooth')hw('bluetooth','choose',{scanMs:5000});
            else if(a==='paired')hw('bluetooth','choose',{bondedOnly:true});
            else if(a==='bleConnect')hw('bluetooth','connect');
            else if(a==='bleStop')hw('bluetooth','disconnect');
            else if(a==='vibrate')hw('haptics','vibrate',{durationMs:150});
            else if(a==='torchOn')hw('torch','set',{enabled:true});
            else if(a==='torchOff')hw('torch','set',{enabled:false});
            else if(a==='battery')hw('battery','get');
            else if(a==='nfc')hw('nfc','read');
            else if(a==='usb')hw('usb','choose');
            else if(a==='usbStop')hw('usb','disconnect');
            else if(a==='infrared')hw('infrared','frequencies');
          }
          if(e.type==='hardware'){
            message=e.event+': '+JSON.stringify(e.data);
            if(e.event==='photo'){photo=e.data.asset;effects.push({type:'storage.save',data:{photo:photo}})}
          }
          if(e.type==='status')message=e.message;
          if(e.type==='pitch')message='Microphone active. Pitch: '+e.hz.toFixed(1)+' Hz';
          if(e.type==='hardwareStopped')message='Hardware stopped. Use a test button to start again.';
          var ui=[{type:'text',text:'Hardware check',style:'subtitle'}];
          if(page==='menu'){
            ui.push(text('Each test uses the same permissions as your own apps.'));
            [['Camera','camera'],['Headphones & microphone','audio'],['Motion sensors','sensors'],['Location','location'],['Bluetooth data devices','bluetooth'],['Other device hardware','other']].forEach(function(x){ui.push(button(x[0],'page:'+x[1]))});
          } else {
            ui.push(button('Back to hardware list','menu'));
            ui.push(text(message));
            if(page==='camera'){ui.push(button('Take a photo','camera'));if(photo)ui.push({type:'image',asset:photo,description:'Captured test photo'});}
            if(page==='audio'){
              ui.push(text('Connect Galaxy Buds in Android settings, then choose the headset route.'));
              ui.push(button('Choose audio device','audioRoute'),button('Play test tone','audioTone'),button('Speak test phrase','audioSpeak'),button('Start microphone','microphone'),button('Stop microphone','microphoneStop'),button('Stop audio','audioStop'),button('Reset audio route','audioReset'));
            }
            if(page==='sensors')ui.push(button('List sensors','sensorList'),button('Start accelerometer','sensor'),button('Stop sensors','sensorStop'));
            if(page==='location')ui.push(button('Get current location','location'));
            if(page==='bluetooth')ui.push(text('BLE and serial data only. For Galaxy Buds sound, use Headphones & microphone.'),button('Choose paired device','paired'),button('Discover nearby BLE devices','bluetooth'),button('Connect chosen BLE device','bleConnect'),button('Disconnect','bleStop'));
            if(page==='other')ui.push(button('Vibrate','vibrate'),button('Flashlight on','torchOn'),button('Flashlight off','torchOff'),button('Read battery status','battery'),button('Read NFC tag','nfc'),button('Choose USB device','usb'),button('Disconnect USB','usbStop'),button('Check infrared emitter','infrared'));
          }
          return JSON.stringify({ui:{type:'column',children:ui},effects:effects});
        }
    """.trimIndent()
}
