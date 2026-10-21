package app.miru.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressResolverTest {
    @Test fun domainsUseHttps() {
        assertEquals(AddressTarget.Web("https://github.com"), AddressResolver.resolve(" github.com "))
        assertEquals(AddressTarget.Web("https://localhost:8080/path"), AddressResolver.resolve("localhost:8080/path"))
        assertEquals(AddressTarget.Web("https://例子.中国/路径"), AddressResolver.resolve("例子.中国/路径"))
    }
    @Test fun searchesAreEncodedAndRespectEngine() {
        assertEquals(AddressTarget.Web("https://www.google.com/search?q=%E6%B5%8F%E8%A7%88%E5%99%A8"), AddressResolver.resolve("浏览器"))
        assertEquals(AddressTarget.Web("https://www.bing.com/search?q=a+%26+b"), AddressResolver.resolve("a & b", SearchEngine.BING))
    }
    @Test fun unsafeSchemesAndCredentialsAreRejected() {
        listOf("javascript:alert(1)", "file:///sdcard/a", "intent://x", "data:text/html,test", "https://user:pass@example.com").forEach {
            assertEquals(AddressTarget.Unsupported, AddressResolver.resolve(it))
        }
    }
    @Test fun internalRoutesStayNative() {
        assertEquals(AddressTarget.Internal("settings"), AddressResolver.resolve("miru://settings"))
        assertEquals(AddressTarget.Internal("newtab"), AddressResolver.resolve("about:newtab"))
        assertEquals(AddressTarget.Empty, AddressResolver.resolve("  "))
    }
    @Test fun displayDoesNotLeakPathOrQuery() {
        assertEquals("m.bilibili.com", AddressResolver.display("https://m.bilibili.com/video/1?token=secret"))
        assertEquals("localhost:8080", AddressResolver.display("http://localhost:8080/test"))
        assertTrue(AddressResolver.display("about:newtab").isEmpty())
    }
}
