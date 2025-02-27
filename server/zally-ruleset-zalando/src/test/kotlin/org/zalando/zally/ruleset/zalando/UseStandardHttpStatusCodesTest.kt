package org.zalando.zally.ruleset.zalando

import org.zalando.zally.core.DefaultContext
import org.zalando.zally.core.DefaultContextFactory
import org.zalando.zally.core.rulesConfig
import org.zalando.zally.rule.api.Context
import org.zalando.zally.ruleset.zalando.util.openApiWithOperations
import org.assertj.core.api.Assertions.assertThat
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
import org.zalando.zally.core.util.HttpStatus

@Suppress("UndocumentedPublicClass")
class UseStandardHttpStatusCodesTest {
    private val rule = UseStandardHttpStatusCodesRule(rulesConfig)

    @Test
    fun `checkWellUnderstoodResponseCodesUsage should return no violations if the usage is correct`() {
        val allowedToAll = listOf(
            "200", "301", "400", "401", "403", "404", "405", "406", "408", "410", "428", "429",
            "500", "501", "503", "default"
        )
        val operations = mapOf(
            "get" to listOf("304") + allowedToAll,
            "post" to listOf("201", "202", "207", "303", "415") + allowedToAll,
            "put" to listOf("201", "202", "204", "303", "409", "412", "415", "423") + allowedToAll,
            "patch" to listOf("202", "204", "303", "409", "412", "415", "423") + allowedToAll,
            "delete" to listOf("202", "204", "303", "409", "412", "415", "423") + allowedToAll
        )

        val openApi = openApiWithOperations(operations)
        val context = DefaultContext("", openApi)

        assertThat(rule.checkWellUnderstoodResponseCodesUsage(context)).isEmpty()
    }

    @Test
    fun `checkWellUnderstoodResponseCodesUsage should return violation if the well-understood HTTP status codes are used incorrectly`() {
        val notAllowedAll = listOf(
            "203", "205", "206", "208", "226", "302", "305", "306", "307", "308", "402", "407", "411",
            "413", "414", "416", "417", "418", "421", "422", "424", "426", "431", "451", "502", "504",
            "505", "506", "507", "508", "510", "511"
        )
        val operations = mapOf(
            "get" to listOf("201", "202", "204", "207", "303", "409", "412", "415", "423") + notAllowedAll,
            "post" to listOf("204", "304", "412", "423") + notAllowedAll,
            "put" to listOf("304") + notAllowedAll,
            "patch" to listOf("201", "304") + notAllowedAll,
            "delete" to listOf("201", "304") + notAllowedAll
        )

        val expectedPointers = operations.flatMap { method ->
            method.value.map { code ->
                "/paths/~1test/${method.key}/responses/$code"
            }
        }

        val openApi = openApiWithOperations(operations)
        val context = DefaultContext("", openApi)
        val violations = rule.checkWellUnderstoodResponseCodesUsage(context)

        assertThat(violations).isNotEmpty
        assertThat(violations.map { it.pointer.toString() }).containsExactlyInAnyOrder(*expectedPointers.toTypedArray())
    }

    @Test
    fun `checkIfOnlyStandardizedResponseCodesAreUsed should return no violation if standardized response code are used`() {
        val context = apiWithGetResponseCode("200")

        val violations = rule.checkIfOnlyStandardizedResponseCodesAreUsed(context)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `checkIfOnlyStandardizedResponseCodesAreUsed should return violation if non-standardized response code is used`() {
        val context = apiWithGetResponseCode("666")

        val violations = rule.checkIfOnlyStandardizedResponseCodesAreUsed(context)

        assertThat(violations).isNotEmpty
        assertThat(violations[0].description).containsPattern(".*666 is not a standardized response code.*")
        assertThat(violations[0].pointer.toString()).isEqualTo("/paths/~1pets/get/responses/666")
    }

    @Test
    fun `checkIfOnlyWellUnderstoodResponseCodesAreUsed should return no violation if well-understood response code is used`() {
        val context = apiWithGetResponseCode("201")

        val violations = rule.checkIfOnlyWellUnderstoodResponseCodesAreUsed(context)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `checkIfOnlyWellUnderstoodResponseCodesAreUsed should return a violation if not well-understood response code is used`() {
        val context = apiWithGetResponseCode("417")

        val violations = rule.checkIfOnlyWellUnderstoodResponseCodesAreUsed(context)

        assertThat(violations).isNotEmpty
        assertThat(violations[0].description).containsPattern(".*417 is not a well-understood response code.*")
        assertThat(violations[0].pointer.toString()).isEqualTo("/paths/~1pets/get/responses/417")
    }

    @Test
    fun `checkThatNoContentStatusCodesHaveNoContent should return violation in case if 204 response has content`() {
        @Language("YAML")
        val context = DefaultContextFactory().getOpenApiContext(
            """
            openapi: 3.0.0
            paths:
              "/shipment-order/{shipment_order_id}":
                put:
                  responses:
                    204:
                      content:
                        "application/json": 
                          schema:
                            type: object
                            properties: 
                              prop-1:
                                type: string
                    200:
                      content:
                        "application/json": 
                          schema:
                            type: object
                            properties: 
                              prop-1:
                                type: string                              
            """.trimIndent()
        )
        val violations = rule.checkThatNoContentResponseHasNoContentDefined(context)
        assertThat(violations).hasSize(1)
        val violation = violations[0]
        assertThat(violation.description).isEqualTo(UseStandardHttpStatusCodesRule.noContentViolationMessage(HttpStatus.NoContent.code.toString()))
    }

    @Test
    fun `checkThatNoContentStatusCodesHaveNoContent return no violation in case if 204 response has no content`() {
        @Language("YAML")
        val context = DefaultContextFactory().getOpenApiContext(
            """
            openapi: 3.0.0
            paths:
              "/orders/{order_id}":
                put:
                  responses:
                    204:
                      description: Successful response
            """.trimIndent()
        )
        val violations = rule.checkThatNoContentResponseHasNoContentDefined(context)
        assertThat(violations).isEmpty()
    }

    @Test
    fun `(checkIfOnlyWellUnderstoodResponseCodesAreUsed, checkIfOnlyStandardizedResponseCodesAreUsed) should return no violation for default response`() {
        val context = apiWithGetResponseCode("default")

        assertThat(rule.checkIfOnlyWellUnderstoodResponseCodesAreUsed(context)).isEmpty()
        assertThat(rule.checkIfOnlyStandardizedResponseCodesAreUsed(context)).isEmpty()
    }

    @Test
    fun `checkIfOnlyStandardizedResponseCodesAreUsed should return operation detail in violation message`() {
        val api = apiWithGetResponseCode("202")
        val violations = rule.checkWellUnderstoodResponseCodesUsage(api)
        assertThat(violations).isNotEmpty
        val violation = violations[0]
        assertThat(violation.description).isEqualTo(UseStandardHttpStatusCodesRule.wellUnderstoodViolationMessage("/pets", "202", "GET"))
    }

    private fun apiWithGetResponseCode(responseCode: String): Context {
        @Language("YAML")
        val content = """
            openapi: '3.0.1'
            paths:
              /pets:
                get:
                  responses:
                    $responseCode: {}
        """.trimIndent()

        return DefaultContextFactory().getOpenApiContext(content)
    }
}
