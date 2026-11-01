package io.github.douyinextractor.ui

import io.github.douyinextractor.core.browser.*
import io.github.douyinextractor.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KuaishouBrowserFlowTest {
    private val dispatcher=StandardTestDispatcher()
    private val id="3xie2x2ahb2weu9"
    private val url="https://www.kuaishou.com/short-video/$id"
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun tearDown(){Dispatchers.resetMain()}
    private fun model(auto:Boolean=true,error:ExtractError=ExtractError.UnsupportedPage)=MainViewModel(
        resolveLink={input,_->Result.success(ResolvedVideoLink(input,url,id,url,emptyList(),"Kuaishou",url))},
        extractMedia={_,_->Result.failure(error)},autoBrowserInitiallyEnabled=auto)
    private fun media(platform:String="Kuaishou",identity:String=id)=MediaInfo(platform,identity,"目标",null,null,null,
        listOf(MediaFormat("https://v1.kwaicdn.com/a.mp4",720,1280,null,"h264","video/mp4",null,"test")))
    @Test fun `native empty page hands off to same Kuaishou work and accepts hydrated result`()=runTest(dispatcher){
        val model=model();model.receiveShare("https://v.kuaishou.com/K2YNoqJJ 快手极速版分享");runCurrent()
        val browser=model.state.value.browserRequest!!
        assertEquals("Kuaishou",browser.platform);assertEquals(id,browser.targetId);assertEquals(KuaishouBrowserPolicy.sharePage(id),browser.url)
        assertFalse(model.state.value.isError)
        model.receiveBrowserSnapshot(browser.sessionId,BrowserSnapshotResult(media(),url,emptyList(),"目标匹配"))
        assertEquals(id,model.state.value.media?.id);assertNull(model.state.value.browserRequest);assertFalse(model.state.value.isError)
    }
    @Test fun `disabled auto browser requires one manual tap and close never reopens itself`()=runTest(dispatcher){
        val model=model(auto=false);model.receiveShare(url);runCurrent();assertNull(model.state.value.browserRequest);assertTrue(model.state.value.browserAvailable)
        model.startBrowser();val first=model.state.value.browserRequest!!.sessionId
        model.finishBrowser(first,"已关闭");advanceTimeBy(120000);runCurrent();assertNull(model.state.value.browserRequest)
        model.startBrowser();assertNotEquals(first,model.state.value.browserRequest!!.sessionId)
    }
    @Test fun `both automatic and manual browser keep the official matching share landing`()=runTest(dispatcher){
        val landing="https://kphm5nf3.m.chenzhongtech.com/fw/photo/$id"
        for(auto in listOf(true,false)){
            val model=MainViewModel(resolveLink={input,_->Result.success(ResolvedVideoLink(input,url,id,"$landing?userId=PRIVATE",emptyList(),"Kuaishou"))},extractMedia={_,_->Result.failure(ExtractError.UnsupportedPage)},autoBrowserInitiallyEnabled=auto)
            model.receiveShare(url);runCurrent();if(!auto)model.startBrowser()
            assertEquals(landing,model.state.value.browserRequest!!.url)
        }
    }
    @Test fun `late callbacks wrong page wrong identity and foreign platform cannot replace result`()=runTest(dispatcher){
        val model=model();model.receiveShare(url);runCurrent();val session=model.state.value.browserRequest!!.sessionId
        for(result in listOf(BrowserSnapshotResult(media(),"https://www.kuaishou.com/short-video/3xotherphoto1234",emptyList(),"wrong"),
            BrowserSnapshotResult(media(identity="3xotherphoto1234"),url,emptyList(),"wrong"),BrowserSnapshotResult(media(platform="Douyin"),url,emptyList(),"wrong")))
            model.receiveBrowserSnapshot(session,result)
        assertNull(model.state.value.media)
        model.editInput("new input");model.receiveBrowserSnapshot(session,BrowserSnapshotResult(media(),url,emptyList(),"late"));assertNull(model.state.value.media)
    }
    @Test fun `verification waits for user while denied deleted login and unsupported media do not auto open`()=runTest(dispatcher){
        val model=model();model.receiveShare(url);runCurrent();val session=model.state.value.browserRequest!!.sessionId
        model.receiveBrowserSnapshot(session,BrowserSnapshotResult(null,url,emptyList(),"verify",ExtractError.KuaishouVerificationRequired))
        assertTrue(model.state.value.message!!.contains("快手"));assertNotNull(model.state.value.browserRequest)
        for(error in listOf(ExtractError.LoginRequired,ExtractError.PlatformRejected,ExtractError.VideoNotFound,ExtractError.UnsupportedMedia)){
            val failed=model(error=error);failed.receiveShare(url);runCurrent();assertNull(failed.state.value.browserRequest);assertFalse(failed.state.value.browserAvailable)
        }
    }
    @Test fun `native success keeps existing direct download route without opening browser`()=runTest(dispatcher){
        val model=MainViewModel(resolveLink={_,_->Result.success(ResolvedVideoLink(url,url,id,url,emptyList(),"Kuaishou"))},extractMedia={_,_->Result.success(media())})
        model.receiveShare(url);runCurrent();assertEquals(id,model.state.value.media?.id);assertNull(model.state.value.browserRequest)
    }
}
