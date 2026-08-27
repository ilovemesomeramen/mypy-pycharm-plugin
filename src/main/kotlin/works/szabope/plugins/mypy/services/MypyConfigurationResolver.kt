package works.szabope.plugins.mypy.services

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.python.sdk.pythonSdk
import kotlinx.coroutines.runBlocking
import works.szabope.plugins.common.services.ToolExecutorConfiguration
import works.szabope.plugins.common.services.ToolSettingsInvalidException
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

class MypyConfigurationResolver(private val project: Project) {

    /** Scan targets grouped by their resolved configuration, plus targets no configuration resolved for. */
    data class TargetGroups(
        val groups: Map<ToolExecutorConfiguration, List<VirtualFile>>,
        val unresolved: List<VirtualFile>
    )

    fun resolveForFile(file: VirtualFile): Result<ToolExecutorConfiguration> {
        val module = ModuleUtilCore.findModuleForFile(file, project)
        if (module == null) {
            thisLogger().debug("[mypy-multi-module] No module found for ${file.path}, using project fallback")
            return projectFallback()
        }
        return resolveForModule(module)
    }

    fun resolveForModule(module: Module): Result<ToolExecutorConfiguration> {
        // 1. Explicit per-module settings take highest priority
        val moduleConfig = MypyModuleSettings.getInstance(project).getModuleConfig(module.name)
        if (moduleConfig != null && moduleConfig.enabled) {
            thisLogger().debug("[mypy-multi-module] Module '${module.name}': using explicit module settings")
            return buildFromModuleConfig(module, moduleConfig)
        }

        // 2. Auto-detect from module SDK
        // In multi-module projects, always try per-module resolution since
        // project.pythonSdk can equal one of the module SDKs (whichever was last set)
        val moduleSdk = module.pythonSdk
        val isMultiModule = ModuleManager.getInstance(project).modules.size > 1
        if (moduleSdk != null && isMultiModule) {
            val mypyPath = findMypyInSdk(moduleSdk)
            if (mypyPath == null) {
                thisLogger().debug("[mypy-multi-module] Module '${module.name}': has SDK '${moduleSdk.name}' but mypy not found in it, using project fallback")
                return projectFallback()
            }
            val workDir = guessModuleContentRoot(module) ?: return projectFallback()
            val projectSettings = MypySettings.getInstance(project)
            thisLogger().debug("[mypy-multi-module] Module '${module.name}': auto-detected mypy=$mypyPath, workDir=$workDir, sdk=${moduleSdk.name}")
            return Result.success(
                ToolExecutorConfiguration(
                    executablePath = mypyPath,
                    useProjectSdk = false,
                    configFilePath = "",
                    arguments = projectSettings.arguments,
                    workingDirectory = workDir,
                    excludeNonProjectFiles = projectSettings.excludeNonProjectFiles,
                    scanBeforeCheckIn = projectSettings.scanBeforeCheckIn
                )
            )
        }

        // 3. Fall back to project-level settings
        thisLogger().debug("[mypy-multi-module] Module '${module.name}': using project fallback (modules=${ModuleManager.getInstance(project).modules.size}, moduleSdk=${moduleSdk?.name ?: "null"})")
        return projectFallback()
    }

    private fun buildFromModuleConfig(
        module: Module,
        moduleConfig: MypyModuleSettings.ModuleConfig
    ): Result<ToolExecutorConfiguration> {
        val projectSettings = MypySettings.getInstance(project)

        val executable = moduleConfig.mypyExecutable?.trim()?.takeIf { it.isNotBlank() }
            ?: module.pythonSdk?.let { findMypyInSdk(it) }
            ?: return Result.failure(
                ToolSettingsInvalidException("Mypy executable not found for module '${module.name}'")
            )

        val workDir = moduleConfig.workingDirectory?.trim()?.takeIf { it.isNotBlank() }
            ?: guessModuleContentRoot(module)
            ?: return Result.failure(
                ToolSettingsInvalidException("Working directory not found for module '${module.name}'")
            )

        return Result.success(
            ToolExecutorConfiguration(
                executablePath = executable,
                useProjectSdk = false,
                configFilePath = moduleConfig.configFilePath?.trim() ?: "",
                arguments = moduleConfig.arguments?.trim()?.takeIf { it.isNotBlank() }
                    ?: projectSettings.arguments,
                workingDirectory = workDir,
                excludeNonProjectFiles = moduleConfig.excludeNonProjectFiles,
                scanBeforeCheckIn = projectSettings.scanBeforeCheckIn
            )
        )
    }

    fun groupByConfiguration(files: Collection<VirtualFile>): TargetGroups {
        val groups = mutableMapOf<ToolExecutorConfiguration, MutableList<VirtualFile>>()
        val unresolved = mutableListOf<VirtualFile>()
        for (file in files) {
            val config = resolveForFile(file).getOrNull()
            if (config == null) {
                unresolved.add(file)
            } else {
                groups.getOrPut(config) { mutableListOf() }.add(file)
            }
        }
        return TargetGroups(groups, unresolved)
    }

    /**
     * Cheap applicability check for AnAction.update(). Mirrors the decision tree of [resolveForFile]
     * without filesystem probes or suspending validation, both of which are too expensive for the
     * action update cycle. May be optimistic: actionPerformed() surfaces a warning when actual
     * resolution fails.
     */
    fun isApplicableForFile(file: VirtualFile): Boolean {
        val module = ModuleUtilCore.findModuleForFile(file, project) ?: return isProjectApplicable()
        return isApplicableForModule(module)
    }

    fun hasAnyApplicableConfiguration(files: Collection<VirtualFile>): Boolean {
        return files.any { isApplicableForFile(it) }
    }

    private fun isApplicableForModule(module: Module): Boolean {
        val moduleConfig = MypyModuleSettings.getInstance(project).getModuleConfig(module.name)
        if (moduleConfig != null && moduleConfig.enabled) {
            return !moduleConfig.mypyExecutable.isNullOrBlank() || module.pythonSdk != null
        }
        if (module.pythonSdk != null && ModuleManager.getInstance(project).modules.size > 1) {
            return true
        }
        return isProjectApplicable()
    }

    private fun isProjectApplicable() = MypySettings.getInstance(project).isToolApplicable()

    private fun projectFallback(): Result<ToolExecutorConfiguration> {
        return runBlocking { MypySettings.getInstance(project).getValidConfiguration() }
    }

    private fun guessModuleContentRoot(module: Module): String? {
        return ModuleRootManager.getInstance(module)
            .contentRoots.firstOrNull()?.canonicalPath
    }

    companion object {
        private val MYPY_CANDIDATES = if (SystemInfo.isWindows) {
            listOf("mypy.exe", "mypy.bat")
        } else {
            listOf("mypy")
        }

        fun findMypyInSdk(sdk: com.intellij.openapi.projectRoots.Sdk): String? {
            val sdkHomePath = sdk.homePath ?: return null
            val binDir = Path(sdkHomePath).parent ?: return null
            for (candidate in MYPY_CANDIDATES) {
                val mypyPath = binDir.resolve(candidate)
                if (mypyPath.exists() && mypyPath.isExecutable()) {
                    return mypyPath.toString()
                }
            }
            return null
        }
    }
}
