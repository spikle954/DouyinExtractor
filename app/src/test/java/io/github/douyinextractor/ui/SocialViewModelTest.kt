package io.github.douyinextractor.ui

import io.github.douyinextractor.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun teardown(){Dispatchers.resetMain()}
    @Test fun `share access reference reaches extractor while diagnostics omit parameters`()=runTest(dispatcher) {
        val id="6411cf99000000001300b6d9";val canonical="https://www.xiaohongshu.com/explore/$id"
        val access="$canonical?xsec_token=SECRET&xsec_source=app_share";var seen=""
        val model=MainViewModel(resolveLink={_,_->Result.success(ResolvedVideoLink(access,canonical,id,access,emptyList(),"Xiaohongshu",access))},
            extractMedia={url,_->seen=url;Result.success(MediaInfo("Xiaohongshu",id,"视频",null,null,null,
                listOf(MediaFormat("https://sns-video-bd.xhscdn.com/a.mp4",720,1280,null,"h264","video/mp4",null,"test"))))})
        model.receiveShare(access);runCurrent()
        assertEquals(access,seen);assertFalse(model.state.value.isError);assertEquals(id,model.state.value.media?.id)
        assertFalse(model.state.value.diagnostics.contains("SECRET"));assertNull(model.state.value.browserRequest)
    }
    @Test fun `Xiaohongshu failure does not open another platforms browser`()=runTest(dispatcher) {
        for((platform,id,url) in listOf(Triple("Xiaohongshu","6411cf99000000001300b6d9","https://www.xiaohongshu.com/explore/6411cf99000000001300b6d9"))) {
            val model=MainViewModel(resolveLink={_,_->Result.success(ResolvedVideoLink(url,url,id,url,emptyList(),platform))},
                extractMedia={_,_->Result.failure(ExtractError.UnsupportedPage)})
            model.receiveShare(url);runCurrent();assertTrue(model.state.value.isError)
            assertNull(model.state.value.browserRequest);assertFalse(model.state.value.browserAvailable)
        }
    }
}
