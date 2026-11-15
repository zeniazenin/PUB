package app.pub.builder

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import app.pub.auth.ModelChoice
import app.pub.core.ProjectBundle
import app.pub.projects.SavedProject

/** Activity ViewModels observe a process-owned controller; clearing an Activity never cancels a build. */
interface BuilderState {
    val backgroundManaged:Boolean
    val buildInProgress:Boolean
    val hasBuildResult:Boolean
    val historySessionId:String
    val capturePending:VisualCaptureRequest?
    val visualReview:String?
    val visualReviewError:String?
    val editingProject:SavedProject?
    val imageToolsUnavailable:Boolean
    val account:String?
    val models:List<ModelChoice>
    val selectedModel:String
    val automaticModelSelection:Boolean
    val modelSelectionNote:String
    var prompt:String
    val busy:String?
    val message:String?
    val draft:ProjectBundle?
    val draftHistoryId:String?
    val history:List<PromptHistoryEntry>
    val historyError:String?
    fun beginEdit(project:SavedProject)
    fun beginCreate()
    fun refreshHistory()
    fun markSaved(historyId:String?,projectId:String,name:String)
    fun setVisible(value:Boolean)
    fun connect()
    fun selectModel(id:String)
    fun useAutomaticModel()
    fun refreshModels()
    fun disconnect()
    fun generate(context:String)
    fun clearDraft()
    fun captureFailed(reason:String)
    fun captureFailed(token:String,reason:String)
    fun reviewCapture(token:String,png:ByteArray)
    fun retryVisualReview(context:String)
    fun cancel()
}

object BuilderControllers {
    private var instance:BuilderController?=null
    @Synchronized fun get(application:Application):BuilderController = instance ?: BuilderController(application).also {instance=it}
    @Synchronized internal fun replaceForTest(value:BuilderController?):BuilderController? {val old=instance;instance=value;return old}
}
class BuilderModel private constructor(application:Application, private val controller:BuilderController, private val owned:Boolean):
    AndroidViewModel(application),BuilderState by controller {
    @JvmOverloads constructor(application:Application,backend:BuilderBackend?=null,historyContext:Context=application):this(
        application,if(backend==null) BuilderControllers.get(application) else BuilderController(application,backend,historyContext),backend!=null)
    companion object {const val MAX_VISUAL_REPAIRS=2}
    override fun onCleared() {if(owned) controller.close();super.onCleared()}
}
