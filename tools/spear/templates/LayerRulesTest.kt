package __BASE_PACKAGE__.architecture

import com.lemonappdev.konsist.core.Konsist
import com.lemonappdev.konsist.core.architecture.Layer
import org.junit.jupiter.api.Test

/**
 * SPEAR layer-rule enforcement.
 *
 * Emitted only for Kotlin projects that already declare Konsist (REQ-065). Mirrors the
 * three-layer hexagonal discipline SPEAR enforces: domain depends on
 * nothing, application depends only on domain, infrastructure is
 * unconstrained.
 *
 * If this project uses a non-standard top-level package, replace
 * `__BASE_PACKAGE__` throughout this file with the actual package.
 * `/spear:init` substitutes the detected package automatically; when
 * copying this template by hand, do the substitution manually.
 */
class LayerRulesTest {

    @Test
    fun `spear layer dependencies are correct`() {
        Konsist
            .scopeFromProduction()
            .assertArchitecture {
                val domain = Layer("Domain", "__BASE_PACKAGE__.domain..")
                val application = Layer("Application", "__BASE_PACKAGE__.application..")
                val infrastructure = Layer("Infrastructure", "__BASE_PACKAGE__.infrastructure..")

                domain.dependsOnNothing()
                application.dependsOn(domain)
                infrastructure.dependsOn(domain, application)
            }
    }

    @Test
    fun `domain has no framework annotations or imports`() {
        val forbiddenPrefixes = listOf(
            "org.springframework",
            "jakarta.persistence",
            "javax.persistence",
            "com.fasterxml.jackson",
            "io.micronaut",
            "lombok",
        )

        Konsist
            .scopeFromProduction()
            .files
            .withPackage("..domain..")
            .assertFalse { file ->
                file.imports.any { import ->
                    forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
                }
            }
    }
}
