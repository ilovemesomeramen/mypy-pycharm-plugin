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
import works.szabope.plugins.common.services.ImmutableSettingsData
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

class MypyConfigurationResolver(private val project: Project) {

    fun resolveForFile(file: VirtualFile): Result<ImmutableSettingsData> {
        val module = ModuleUtilCore.findModuleForFile(file, project)
        if (module == null) {
            thisLogger().debug("[mypy-multi-module] No module found for ${file.path}, using project fallback")
            return projectFallback()
        }
        return resolveForModule(module)
    }

    fun resolveForModule(module: Module): Result<ImmutableSettingsData> {
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
                MypyExecutorConfiguration(
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
    ): Result<ImmutableSettingsData> {
        val projectSettings = MypySettings.getInstance(project)

        val executable = moduleConfig.mypyExecutable?.trim()?.takeIf { it.isNotBlank() }
            ?: module.pythonSdk?.let { findMypyInSdk(it) }
            ?: return Result.failure(
                MypySettingsInvalid("Mypy executable not found for module '${module.name}'")
            )

        val workDir = moduleConfig.workingDirectory?.trim()?.takeIf { it.isNotBlank() }
            ?: guessModuleContentRoot(module)
            ?: return Result.failure(
                MypySettingsInvalid("Working directory not found for module '${module.name}'")
            )

        return Result.success(
            MypyExecutorConfiguration(
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

    fun groupByConfiguration(
        files: Collection<VirtualFile>
    ): Map<MypyExecutorConfiguration, List<VirtualFile>> {
        val result = mutableMapOf<MypyExecutorConfiguration, MutableList<VirtualFile>>()
        for (file in files) {
            val config = resolveForFile(file).getOrNull() ?: continue
            val key = config as MypyExecutorConfiguration
            result.getOrPut(key) { mutableListOf() }.add(file)
        }
        return result
    }

    fun hasAnyValidConfiguration(files: Collection<VirtualFile>): Boolean {
        return files.any { resolveForFile(it).isSuccess }
    }

    private fun projectFallback(): Result<ImmutableSettingsData> {
        return MypySettings.getInstance(project).getValidConfiguration()
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
