package works.szabope.plugins.mypy.services

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.TestOnly

@Service(Service.Level.PROJECT)
@State(name = "MypyModuleSettings", storages = [Storage("MypyPlugin.xml")], category = SettingsCategory.PLUGINS)
class MypyModuleSettings(internal val project: Project) :
    SimplePersistentStateComponent<MypyModuleSettings.MypyModuleSettingsState>(MypyModuleSettingsState()) {

    class ModuleConfig : BaseState() {
        var moduleName by string()
        var enabled by property(false)
        var mypyExecutable by string()
        var configFilePath by string()
        var arguments by string()
        var workingDirectory by string()
        var excludeNonProjectFiles by property(true)
    }

    class MypyModuleSettingsState : BaseState() {
        var moduleConfigs by list<ModuleConfig>()
    }

    fun getModuleConfig(moduleName: String): ModuleConfig? {
        return state.moduleConfigs.firstOrNull { it.moduleName == moduleName }
    }

    fun getOrCreateModuleConfig(moduleName: String): ModuleConfig {
        return getModuleConfig(moduleName) ?: ModuleConfig().also {
            it.moduleName = moduleName
            state.moduleConfigs.add(it)
        }
    }

    fun removeModuleConfig(moduleName: String) {
        state.moduleConfigs.removeAll { it.moduleName == moduleName }
    }

    @TestOnly
    fun reset() {
        loadState(MypyModuleSettingsState())
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): MypyModuleSettings = project.service()
    }
}
