package works.szabope.plugins.mypy.configurable

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.dsl.builder.*
import com.jetbrains.python.sdk.pythonSdk
import works.szabope.plugins.mypy.MypyBundle
import works.szabope.plugins.mypy.services.MypyConfigurationResolver
import works.szabope.plugins.mypy.services.MypyModuleSettings
import javax.swing.JComponent

class MypyModuleConfigurable(private val project: Project) : Configurable {

    private data class ModuleRow(
        val moduleName: String,
        var enabled: Boolean = false,
        var mypyExecutable: String = "",
        var configFilePath: String = "",
        var arguments: String = "",
        var workingDirectory: String = "",
        var excludeNonProjectFiles: Boolean = true,
        val detectedExecutable: String? = null,
        val moduleSdkName: String? = null
    )

    private val moduleRows = mutableListOf<ModuleRow>()
    private var mainPanel: JComponent? = null

    override fun getDisplayName(): String = MypyBundle.message("mypy.configuration.module_settings.name")

    override fun createComponent(): JComponent {
        loadFromSettings()
        val component = buildPanel()
        mainPanel = component
        return component
    }

    private fun buildPanel(): JComponent = panel {
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

    private fun loadFromSettings() {
        moduleRows.clear()
        val moduleSettings = MypyModuleSettings.getInstance(project)
        val modules = ModuleManager.getInstance(project).modules.sortedBy { it.name }
        for (module in modules) {
            val config = moduleSettings.getModuleConfig(module.name)
            val moduleSdk = module.pythonSdk
            val detectedExe = moduleSdk?.let { MypyConfigurationResolver.findMypyInSdk(it) }
            val defaultWorkDir = ModuleRootManager.getInstance(module)
                .contentRoots.firstOrNull()?.canonicalPath ?: ""
            moduleRows.add(
                ModuleRow(
                    moduleName = module.name,
                    enabled = config?.enabled ?: false,
                    mypyExecutable = config?.mypyExecutable?.trim() ?: "",
                    configFilePath = config?.configFilePath?.trim() ?: "",
                    arguments = config?.arguments?.trim() ?: "",
                    workingDirectory = config?.workingDirectory?.trim()?.takeIf { it.isNotBlank() }
                        ?: defaultWorkDir,
                    excludeNonProjectFiles = config?.excludeNonProjectFiles ?: true,
                    detectedExecutable = detectedExe,
                    moduleSdkName = moduleSdk?.name
                )
            )
        }
    }

    override fun isModified(): Boolean {
        val moduleSettings = MypyModuleSettings.getInstance(project)
        for (row in moduleRows) {
            val config = moduleSettings.getModuleConfig(row.moduleName)
            if (config == null) {
                if (row.enabled) return true
            } else {
                if (row.enabled != config.enabled) return true
                if (row.enabled) {
                    if ((row.mypyExecutable) != (config.mypyExecutable?.trim() ?: "")) return true
                    if ((row.configFilePath) != (config.configFilePath?.trim() ?: "")) return true
                    if ((row.arguments) != (config.arguments?.trim() ?: "")) return true
                    if ((row.workingDirectory) != (config.workingDirectory?.trim() ?: "")) return true
                    if (row.excludeNonProjectFiles != config.excludeNonProjectFiles) return true
                }
            }
        }
        return false
    }

    override fun apply() {
        val moduleSettings = MypyModuleSettings.getInstance(project)
        for (row in moduleRows) {
            if (row.enabled) {
                val config = moduleSettings.getOrCreateModuleConfig(row.moduleName)
                config.enabled = true
                config.mypyExecutable = row.mypyExecutable.ifBlank { null }
                config.configFilePath = row.configFilePath.ifBlank { null }
                config.arguments = row.arguments.ifBlank { null }
                config.workingDirectory = row.workingDirectory.ifBlank { null }
                config.excludeNonProjectFiles = row.excludeNonProjectFiles
            } else {
                moduleSettings.removeModuleConfig(row.moduleName)
            }
        }
    }

    override fun reset() {
        loadFromSettings()
        mainPanel?.let {
            val parent = it.parent
            if (parent != null) {
                val idx = parent.components.indexOf(it)
                parent.remove(it)
                val newPanel = buildPanel()
                mainPanel = newPanel
                parent.add(newPanel, idx)
                parent.revalidate()
                parent.repaint()
            }
        }
    }

    companion object {
        const val ID = "Settings.Mypy.Modules"
    }
}
