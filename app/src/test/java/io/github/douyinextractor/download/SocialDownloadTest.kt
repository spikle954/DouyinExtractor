package io.github.douyinextractor.download

import io.github.douyinextractor.core.download.DownloadPhase
import org.junit.Assert.*
import org.junit.Test

class SocialDownloadTest {
    private val ks="3xgp7zntw6w2zqq"
    private val xhs="6411cf99000000001300b6d9"
    private fun item(platform:String,id:String,token:String)=QueuedDownload(
        DownloadRecord(token=token,platform=platform,videoId=id,fileName="video.mp4",title="公开作品",quality="720p"),
        DownloadRequest(token,id,"video.mp4","https://sns-video-bd.xhscdn.com/a.mp4?token=SECRET",null,platform))
    @Test fun `new platform queue journals retain identities but never media or share credentials`() {
        val items=listOf(item("Kuaishou",ks,"abc-1"),item("Xiaohongshu",xhs,"abc-2"))
        val map=DownloadQueueCodec.encode(items);assertFalse(map.toString().contains("SECRET"))
        val restored=DownloadQueueCodec.decode(map)
        assertEquals(items.map {it.identity},restored.map {it.identity})
        assertTrue(restored.all {it.request==null})
        val queue=DownloadQueue();queue.restore(restored)
        assertTrue(queue.pending().all {it.needsReparse&&it.record.phase==DownloadPhase.INTERRUPTED})
    }
    @Test fun `social queue recovery refuses invalid IDs and unsupported parts`() {
        val encoded=DownloadQueueCodec.encode(listOf(item("Xiaohongshu",xhs,"abc-1")))
        assertTrue(DownloadQueueCodec.decode(encoded+mapOf("0.id" to "../unsafe")).isEmpty())
        assertTrue(DownloadQueueCodec.decode(encoded+mapOf("0.page" to "2")).isEmpty())
    }
    @Test fun `retry targets and legacy name identification keep platforms separate`() {
        assertEquals("https://www.kuaishou.com/short-video/$ks",DownloadIdentity("Kuaishou",ks).reparseUrl)
        assertEquals("https://www.xiaohongshu.com/explore/$xhs",DownloadIdentity("Xiaohongshu",xhs).reparseUrl)
        assertEquals(DownloadIdentity("Kuaishou",ks),DownloadIdentity.fromFileName("作者_作品_KS_$ks.mp4"))
        assertEquals(DownloadIdentity("Xiaohongshu",xhs),DownloadIdentity.fromFileName("作者_作品_XHS_$xhs (2).mp4"))
        assertEquals(DownloadIdentity("Douyin","7690145822168750565"),DownloadIdentity.fromFileName("作品_7690145822168750565.mp4"))
    }
    @Test fun `new platforms participate in record search and duplicate detection`() {
        val items=listOf(SavedDownload("content://owned/1","视频.mp4","Movies/拾影",null,100,1,"Kuaishou",ks),
            SavedDownload("content://owned/2","视频.mp4","Movies/拾影",null,100,2,"Xiaohongshu",xhs))
        assertEquals(items.take(1),SavedDownloadHistory.search(items,"快手"))
        assertEquals(items.drop(1),SavedDownloadHistory.search(items,"小红书"))
        assertEquals(items.last(),SavedDuplicateFinder.find(items,DownloadIdentity("Xiaohongshu",xhs)) {SavedFileAccess.AVAILABLE}?.item)
        assertNull(SavedDuplicateFinder.find(items,DownloadIdentity("Douyin",ks)) {SavedFileAccess.AVAILABLE})
    }
}
