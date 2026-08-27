package works.szabope.plugins.mypy.configurable

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.dsl.builder.*
import com.jetbrains.python.sdk.pythonSdk
import works.szabope.plugins.mypy.MypyBundle
import works.szabope.plugins.mypy.services.MypyConfigurationResolver
import works.szabope.plugins.mypy.services.MypyModuleSettings
import javax.swing.JComponent

class MypyModuleConfigurable(private val project: Project) : Configurable {

    private class ModuleRow(
        val moduleName: String,
        var enabled: Boolean = false,
        var mypyExecutable: String = "",
        var configFilePath: String = "",
        var arguments: String = "",
        var workingDirectory: String = "",
        var excludeNonProjectFiles: Boolean = true,
        val detectedExecutable: String? = null,
        val moduleSdkName: String? = null,
        val defaultWorkDir: String = ""
    )

    private val moduleRows = mutableListOf<ModuleRow>()
    private var mainPanel: DialogPanel? = null

    override fun getDisplayName(): String = MypyBundle.message("mypy.configuration.module_settings.name")

    override fun createComponent(): JComponent {
        createRows()
        return buildPanel().also { mainPanel = it }
    }

    private fun buildPanel(): DialogPanel = panel {
        if (moduleRows.isEmpty()) {
            row {
                label(MypyBundle.message("mypy.configuration.module_settings.no_modules"))
            }
            return@panel
        }

        for (row in moduleRows) {
            collapsibleGroup(row.moduleName) {
                lateinit var enabledPredicate: ComponentPredicate
                row {
                    enabledPredicate = checkBox(MypyBundle.message("mypy.configuration.module_settings.enabled"))
                        .bindSelected(
                            { row.enabled },
                            { row.enabled = it }
                        ).selected
                }
                row(MypyBundle.message("mypy.configuration.module_settings.executable")) {
                    textFieldWithBrowseButton(
                        fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                    ).bindText(
                        { row.mypyExecutable },
                        { row.mypyExecutable = it }
                    ).comment(
                        row.detectedExecutable?.let {
                            MypyBundle.message("mypy.configuration.module_settings.auto_detected", it)
                        } ?: row.moduleSdkName?.let {
                            MypyBundle.message("mypy.configuration.module_settings.no_mypy_in_sdk", it)
                        } ?: MypyBundle.message("mypy.configuration.module_settings.no_sdk")
                    ).align(AlignX.FILL)
                        .enabledIf(enabledPredicate)
                }
                row(MypyBundle.message("mypy.configuration.module_settings.config_file")) {
                    textFieldWithBrowseButton(
                        fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                    ).bindText(
                        { row.configFilePath },
                        { row.configFilePath = it }
                    ).comment(MypyBundle.message("mypy.configuration.module_settings.config_file_comment"))
                        .align(AlignX.FILL)
                        .enabledIf(enabledPredicate)
                }
                row(MypyBundle.message("mypy.configuration.module_settings.arguments")) {
                    textField().bindText(
                        { row.arguments },
                        { row.arguments = it }
                    ).align(AlignX.FILL)
                        .enabledIf(enabledPredicate)
                }
                row(MypyBundle.message("mypy.configuration.module_settings.working_directory")) {
                    textFieldWithBrowseButton(
                        fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    ).bindText(
                        { row.workingDirectory },
                        { row.workingDirectory = it }
                    ).comment(
                        row.defaultWorkDir.takeIf { it.isNotBlank() }?.let {
                            MypyBundle.message("mypy.configuration.module_settings.working_directory_detected", it)
                        } ?: MypyBundle.message("mypy.configuration.module_settings.working_directory_comment")
                    ).align(AlignX.FILL)
                        .enabledIf(enabledPredicate)
                }
                row {
                    checkBox(MypyBundle.message("mypy.configuration.module_settings.exclude_non_project"))
                        .bindSelected(
                            { row.excludeNonProjectFiles },
                            { row.excludeNonProjectFiles = it }
                        ).enabledIf(enabledPredicate)
                }
            }
        }
    }

    private fun createRows() {
        moduleRows.clear()
        val modules = ModuleManager.getInstance(project).modules.sortedBy { it.name }
        for (module in modules) {
            val moduleSdk = module.pythonSdk
            val row = ModuleRow(
                moduleName = module.name,
                detectedExecutable = moduleSdk?.let { MypyConfigurationResolver.findMypyInSdk(it) },
                moduleSdkName = moduleSdk?.name,
                defaultWorkDir = ModuleRootManager.getInstance(module).contentRoots.firstOrNull()?.canonicalPath ?: ""
            )
            row.refreshFromSettings()
            moduleRows.add(row)
        }
    }

    private fun ModuleRow.refreshFromSettings() {
        val config = MypyModuleSettings.getInstance(project).getModuleConfig(moduleName)
        enabled = config?.enabled ?: false
        mypyExecutable = config?.mypyExecutable ?: ""
        configFilePath = config?.configFilePath ?: ""
        arguments = config?.arguments ?: ""
        workingDirectory = config?.workingDirectory ?: ""
        excludeNonProjectFiles = config?.excludeNonProjectFiles ?: true
    }

    override fun isModified(): Boolean {
        val moduleSettings = MypyModuleSettings.getInstance(project)
        return moduleRows.any { row ->
            val config = moduleSettings.getModuleConfig(row.moduleName)
            if (config == null) {
                row.enabled
            } else {
                row.enabled != config.enabled
                        || row.mypyExecutable.trim() != (config.mypyExecutable ?: "")
                        || row.configFilePath.trim() != (config.configFilePath ?: "")
                        || row.arguments.trim() != (config.arguments ?: "")
                        || row.workingDirectory.trim() != (config.workingDirectory ?: "")
                        || row.excludeNonProjectFiles != config.excludeNonProjectFiles
            }
        }
    }

    override fun apply() {
        val moduleSettings = MypyModuleSettings.getInstance(project)
        for (row in moduleRows) {
            // Never create a config for a module that was never enabled; keep existing configs
            // (including their field values) when disabled, so re-enabling restores them.
            if (!row.enabled && moduleSettings.getModuleConfig(row.moduleName) == null) continue
            val config = moduleSettings.getOrCreateModuleConfig(row.moduleName)
            config.enabled = row.enabled
            config.mypyExecutable = row.mypyExecutable.trim().ifBlank { null }
            config.configFilePath = row.configFilePath.trim().ifBlank { null }
            config.arguments = row.arguments.trim().ifBlank { null }
            config.workingDirectory = row.workingDirectory.trim().ifBlank { null }
            config.excludeNonProjectFiles = row.excludeNonProjectFiles
        }
    }

    override fun reset() {
        moduleRows.forEach { it.refreshFromSettings() }
        mainPanel?.reset()
    }

    override fun disposeUIResources() {
        mainPanel = null
    }

    companion object {
        const val ID = "Settings.Mypy.Modules"
    }
}
