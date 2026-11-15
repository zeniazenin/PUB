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
import app.pub.hardware.HardwareInventory

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardwareAccessSettings(host:HostModel,onAndroidSettings:()->Unit) {
    var cap by rememberSaveable {mutableStateOf("microphone")}
    var capsOpen by remember {mutableStateOf(false)}
    var projectsOpen by remember {mutableStateOf(false)}
    var projectId by rememberSaveable {mutableStateOf("guitar-tuner")}
    val groups=linkedMapOf("microphone" to "Microphone") + HardwareInventory.labels
    val choices=listOf("guitar-tuner" to "Guitar Tuner")+host.projects.map {it.id to it.bundle.name}
    val selected=choices.find {it.first==projectId} ?: choices.first()
    val mic=cap=="microphone"
    val block=if(mic) host.globalBlocked else host.hardwareBlocked(cap)
    val default=if(mic) host.globalDefault else host.hardwareDefault(cap)
    val own=if(mic) host.microphoneRuleFor(selected.first) else host.hardwareRule(selected.first,cap)
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
    Box {
        OutlinedButton(onClick={projectsOpen=true},modifier=Modifier.fillMaxWidth().testTag("project-permission-picker")){Text(selected.second)}
        DropdownMenu(expanded=projectsOpen,onDismissRequest={projectsOpen=false}) {choices.forEach {(id,name)->DropdownMenuItem(text={Text(name)},onClick={projectId=id;projectsOpen=false})}}
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(Rule.INHERIT to "Use default",Rule.ASK to "Ask",Rule.ALLOW to "Allow",Rule.DENY to "Deny").forEach {(rule,label)->
            FilterChip(selected=own==rule,onClick={if(mic) host.changeProjectRule(selected.first,rule) else host.changeHardwareRule(selected.first,cap,rule)},label={Text(label)})
        }
    }
    Text("Apps must declare the capability. Android permissions and device selection still apply. Changing access stops active hardware sessions.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick=onAndroidSettings){Text("Android app permissions")}
}
