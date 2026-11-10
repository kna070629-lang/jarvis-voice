package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the local capability plugins.
 *
 * The arithmetic plugin is checked thoroughly because it evaluates text, so
 * it has to refuse anything that is not plain arithmetic.
 */
class LocalActionsTest {

    @Test fun theCalculationPluginEvaluatesBasicArithmetic() {
        val action = CalculationAction()
        assertEquals("42", action.execute(null, mapOf("expression" to "12 + 30")).detail["value"])
        assertEquals("25", action.execute(null, mapOf("expression" to "100 / 4")).detail["value"])
        assertEquals("7", action.execute(null, mapOf("expression" to "3 + 2 * 2")).detail["value"])
        assertEquals("1", action.execute(null, mapOf("expression" to "(3 + 2) - 4")).detail["value"])
    }

    @Test fun theCalculationPluginRefusesAnythingThatIsNotArithmetic() {
        val action = CalculationAction()
        for (expression in listOf("rm -rf home", "1; ls", "os.system('x')", "__import__('os')")) {
            val outcome = action.execute(null, mapOf("expression" to expression))
            assertTrue("'$expression' should be refused", !outcome.succeeded)
        }
    }

    @Test fun theCalculationPluginRefusesAnEmptyExpression() {
        val outcome = CalculationAction().execute(null, mapOf("expression" to ""))
        assertTrue(!outcome.succeeded)
    }

    @Test fun theCalculationPluginRefusesAnOverlongExpression() {
        val outcome = CalculationAction().execute(null, mapOf("expression" to "1+".repeat(200)))
        assertTrue(!outcome.succeeded)
    }

    @Test fun everyPluginDeclaresAUniqueIdentifierAndDescription() {
        val registry = ActionPluginRegistry(null)
        val plugins = registry.all()
        assertTrue(plugins.size >= 7)
        assertEquals(plugins.size, plugins.map { it.id }.toSet().size)
        for (plugin in plugins) {
            assertTrue(plugin.id.isNotBlank())
            assertTrue(plugin.description.isNotBlank())
        }
    }

    @Test fun theRegistryReportsTheCapabilitiesItHas() {
        val described = ActionPluginRegistry(null).describe()
        assertTrue(described.isNotEmpty())
        assertTrue(described.any { it.contains("device.battery") })
        assertTrue(described.any { it.contains("device.calculate") })
    }
}
