package app.miru.downloads

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DownloadLogicTest {
    @Test fun rangesCoverEveryByteExactlyOnce() {
        val size=5L*1024*1024+3
        val ranges=DownloadLogic.ranges(size,4,true)
        assertEquals(4,ranges.size);assertEquals(0,ranges.first().start);assertEquals(size-1,ranges.last().end)
        assertEquals(size,ranges.sumOf { it.end-it.start+1 })
        ranges.zipWithNext().forEach { (a,b)->assertEquals(a.end+1,b.start) }
        assertEquals(1,DownloadLogic.ranges(1024,8,true).size)
        assertEquals(1,DownloadLogic.ranges(size,8,false).size)
    }
    @Test fun filenameUsesDispositionAndSafeCharacters() {
        assertEquals("测试.pdf",DownloadLogic.fileName("attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95.pdf","https://a.test/wrong.zip","application/pdf"))
        assertEquals("a+b.zip",DownloadLogic.fileName(null,"https://a.test/a+b.zip?token=secret",null))
        assertEquals("unsafe_name.pdf",DownloadLogic.fileName("attachment; filename=\"unsafe/name.pdf\"","https://a.test",null))
        assertEquals("file (2).zip",DownloadLogic.uniqueName("file.zip"){it in setOf("file.zip","file (1).zip")})
    }
    @Test fun changedOrMissingValidatorInvalidatesExistingRanges() {
        assertTrue(DownloadLogic.validatorsChanged("a",null,100,"b",null,100))
        assertTrue(DownloadLogic.validatorsChanged("a",null,100,null,null,100))
        assertTrue(DownloadLogic.validatorsChanged(null,"date",100,null,"new date",100))
        assertFalse(DownloadLogic.validatorsChanged("a",null,100,"a",null,100))
        assertTrue(DownloadLogic.validatorsChanged("W/\"stable\"","date1",100,"W/\"stable\"","date2",100))
        assertTrue(DownloadLogic.validatorsChanged("W/\"stable\"","date1",100,"W/\"stable\"",null,100))
        assertFalse(DownloadLogic.validatorsChanged("W/\"stable\"","date1",100,"W/\"stable\"","date1",100))
    }
    @Test fun weakEtagAloneCannotProtectPersistedByteRanges() {
        assertFalse(DownloadLogic.canResume("W/\"same-semantics\"", null))
        assertFalse(DownloadLogic.canResume("w/\"same-semantics\"", null))
        assertFalse(DownloadLogic.canResume(null, null))
        assertFalse(DownloadLogic.canResume("", ""))
        assertTrue(DownloadLogic.canResume("\"same-bytes\"", null))
        assertTrue(DownloadLogic.canResume("W/\"same-semantics\"", "Wed, 21 Oct 2026 10:00:00 GMT"))
        assertTrue(DownloadLogic.canResume(null, "Wed, 21 Oct 2026 10:00:00 GMT"))
    }
    @Test fun transportRejectsServerIgnoringRangeWithoutWritingWholeFile()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("wrong full body"))
            val file=File.createTempFile("miru-range",".tmp")
            try {
                try { DownloadTransfer().segment("test",server.url("/").toString(),emptyMap(),DownloadSegment("test",0,2,4),file,true,"\"v1\"",totalSize=6){};fail("Range must fail") } catch(error:java.io.IOException){assertEquals("RANGE_CHANGED",error.message)}
                assertEquals(0,file.length())
            } finally {file.delete()}
        }
    }
    @Test fun transportResumesAtSavedOffsetAndProducesExpectedBytes()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 2-5/6").setHeader("ETag","\"v1\"").setBody("cdef"))
            val file=File.createTempFile("miru-resume",".tmp").apply {writeText("ab")}
            try {
                var saved=0L
                DownloadTransfer().segment("test",server.url("/").toString(),emptyMap(),DownloadSegment("test",0,0,5,2),file,true,"\"v1\"",totalSize=6){saved=it}
                assertEquals("abcdef",file.readText());assertEquals(6,saved)
                assertEquals("bytes=2-5",server.takeRequest().getHeader("Range"))
            }finally{file.delete()}
        }
    }
    @Test fun redirectNeverForwardsAuthenticationToAnotherOrigin()=runBlocking {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location",target.url("/file")))
            target.enqueue(MockResponse().setBody("safe"))
            val file=File.createTempFile("miru-redirect",".tmp")
            try {
                DownloadTransfer().segment("redirect",source.url("/file").toString(),mapOf("Cookie" to "session=secret","Authorization" to "Bearer secret","Origin" to "https://private.test"),DownloadSegment("redirect",0,0,3),file,false,null){}
                val request=target.takeRequest()
                assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Origin"))
                assertEquals("safe",file.readText())
            }finally{file.delete()}
        } }
    }
    @Test fun temporaryServerFailureRetriesAndFinishes()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("good"))
            val file=File.createTempFile("miru-retry",".tmp")
            try {
                DownloadTransfer().segment("retry",server.url("/").toString(),emptyMap(),DownloadSegment("retry",0,0,3),file,false,null){}
                assertEquals("good",file.readText());assertEquals(2,server.requestCount)
            }finally{file.delete()}
        }
    }
}
