package works.szabope.plugins.mypy.services

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.ModuleListener
import com.intellij.openapi.project.Project
import com.intellij.util.Function

/**
 * Keeps per-module configs attached to their module across renames (configs are keyed by module name).
 * Configs of removed modules are intentionally kept: detaching and re-attaching a project — the primary
 * multi-module workflow — removes and re-adds its module, and the config must survive that.
 */
internal class MypyModuleRenameListener : ModuleListener {

    override fun modulesRenamed(
        project: Project, modules: List<Module>, oldNameProvider: Function<in Module, String>
    ) {
        val settings = MypyModuleSettings.getInstance(project)
        for (module in modules) {
            settings.renameModuleConfig(oldNameProvider.`fun`(module), module.name)
        }
    }
}
