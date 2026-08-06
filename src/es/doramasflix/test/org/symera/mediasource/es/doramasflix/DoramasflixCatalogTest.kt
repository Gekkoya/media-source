package org.symera.mediasource.es.doramasflix

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.symera.source.model.PageRequest

class DoramasflixCatalogTest {
    @Test
    fun `movie pagination uses created descending and requested page size`() {
        val variables = moviePaginationVariables(PageRequest(page = 2, pageSize = 24))

        assertEquals(2, variables["page"]?.jsonPrimitive?.int)
        assertEquals(24, variables["perPage"]?.jsonPrimitive?.int)
        assertEquals("CREATEDAT_DESC", variables["sort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `series pagination uses created descending and fallback page size`() {
        val variables = seriesPaginationVariables(PageRequest(page = 1))

        assertEquals(1, variables["page"]?.jsonPrimitive?.int)
        assertEquals(20, variables["perPage"]?.jsonPrimitive?.int)
        assertEquals("CREATEDAT_DESC", variables["sort"]?.jsonPrimitive?.content)
    }
}
