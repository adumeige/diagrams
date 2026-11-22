package diagrams.processor

import diagrams.model.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScopesTest {
    private val index = NodeIndex(
        Yaml.parse(
            """
            scene:
              children:
                - id: title
                - id: card
                  children:
                    - children:
                        - id: title
                        - id: body
                - id: other
                  children:
                    - id: body
            """.trimIndent(),
        ).value,
    )

    private fun node(path: String) = index.resolve(path, index.root!!)!!.node

    @Test
    fun nearestScopeWins() {
        val body = node("card.body")
        assertEquals("card.title", index.resolve("title", body)!!.node.path, "card's own title, not the root's")
        assertEquals("title", index.resolve("scene.title", body)!!.node.path)
        assertEquals("other.body", index.resolve("other.body", body)!!.node.path)
    }

    @Test
    fun unnamedContainersAreTransparentAndTheRestIsReturned() {
        val m = index.resolve("card.body.north.x", index.root!!)!!
        assertEquals("card.body", m.node.path)
        assertEquals(listOf("north", "x"), m.rest)
        assertNull(index.resolve("nobody", node("other")))
    }
}
