package io.github.douyinextractor.core.browser

import io.github.douyinextractor.core.extractor.social.SocialUrls

/** Observe the site's existing public response. No new request, cookie access or native bridge. */
object KuaishouBrowserCapture {
    val install: String = """
        (function(){'use strict';
          try {
            if(window.__SHIYING_KS_CAPTURE__) return;
            var box={details:[]};Object.defineProperty(window,'__SHIYING_KS_CAPTURE__',{value:box,configurable:false});
            function permitted(input){try{var u=new URL(String(input),location.href);return u.protocol==='https:'&&!u.username&&!u.password&&(!u.port||u.port==='443')&&u.hostname===location.hostname&&u.pathname==='/graphql'&&/^(www\.)?kuaishou\.com$/.test(u.hostname);}catch(e){return false;}}
            function save(text){try{if(typeof text!=='string'||text.length>1048576)return;var j=JSON.parse(text),d=j&&j.data&&j.data.visionVideoDetail,p=d&&d.photo;
              if(!p||typeof p.id!=='string'||!/^[A-Za-z0-9]{8,32}$/.test(p.id))return;
              // Save only public detail metadata, never headers, operation variables or account data.
              var keys=['id','photoType','caption','userName','duration','coverUrl','photoUrl','photoH265Url','width','height','manifest','manifestH265'];
              var photo=Object.create(null);for(var i=0;i<keys.length;i++)if(Object.prototype.hasOwnProperty.call(p,keys[i]))photo[keys[i]]=p[keys[i]];
              var a=d.author,author=a?{name:a.name}:null,value=JSON.stringify({photo:photo,author:author});if(value.length>1048576)return;
              box.details.push(value);if(box.details.length>4)box.details.shift();
            }catch(e){}}
            if(typeof window.fetch==='function'){
              var original=window.fetch;window.fetch=function(){var args=arguments,result=original.apply(this,args),input=args[0],url;try{url=typeof input==='string'?input:input&&input.url;}catch(e){return result;}
                if(permitted(url))result.then(function(response){try{var clone=response.clone(),reader=clone.body&&clone.body.getReader();if(!reader)return;
                  var chunks=[],size=0;function read(){return reader.read().then(function(part){if(part.done){var data=new Uint8Array(size),at=0;for(var i=0;i<chunks.length;i++){data.set(chunks[i],at);at+=chunks[i].length;}save(new TextDecoder('utf-8').decode(data));return;}
                    size+=part.value.length;if(size>1048576){reader.cancel().catch(function(){});return;}chunks.push(part.value);return read();});}read().catch(function(){});
                }catch(e){}},function(){});return result;};
            }
            if(window.XMLHttpRequest&&window.WeakMap){
              var urls=new WeakMap(),proto=XMLHttpRequest.prototype,open=proto.open;
              proto.open=function(method,url){var result=open.apply(this,arguments);urls.set(this,permitted(url));return result;};
              var send=proto.send;proto.send=function(){var xhr=this;if(urls.get(xhr))xhr.addEventListener('load',function(){try{if(!xhr.responseType||xhr.responseType==='text')save(xhr.responseText);else if(xhr.responseType==='json')save(JSON.stringify(xhr.response));}catch(e){}},{once:true});return send.apply(this,arguments);};
            }
          }catch(e){}
        })()
    """.trimIndent()

    fun snapshot(id: String): String {
        require(SocialUrls.validId(SocialUrls.KUAISHOU, id))
        return expression.replace("__TARGET_ID__", id)
    }
    private val expression = """
        (function(){'use strict';var target='__TARGET_ID__',budget=1048576,nodes=0;
          function copy(value,depth,seen){if(++nodes>12000||depth>32)throw Error('limit');if(value===null||typeof value==='number'||typeof value==='boolean')return value;
            if(typeof value==='string'){budget-=value.length;if(budget<0)throw Error('limit');return value;}if(typeof value!=='object')return null;
            if(seen.indexOf(value)!==-1)throw Error('cycle');seen.push(value);var out=Array.isArray(value)?[]:Object.create(null),keys=Object.keys(value);if(keys.length>12000)throw Error('limit');
            for(var i=0;i<keys.length;i++){var k=keys[i];if(k==='__proto__'||k==='constructor'||k==='prototype')continue;budget-=k.length;if(budget<0)throw Error('limit');var d=Object.getOwnPropertyDescriptor(value,k);if(d&&Object.prototype.hasOwnProperty.call(d,'value'))out[k]=copy(d.value,depth+1,seen);}seen.pop();return out;}
          var result={version:'ks-1',pageUrl:String(location.href),readyState:String(document.readyState),states:[],details:[],components:[],blocker:null};
          function state(name,value){if(value==null)return;try{var json=JSON.stringify(copy(value,0,[]));if(json&&json.length<=1048576)result.states.push({name:name,json:json});}catch(e){}}
          // Vue makes the selected photo and normalized Apollo references reactive getters.
          // Read only fixed public media fields and references of this exact target, not arbitrary state.
          var mediaKeys=['id','photoId','photoType','caption','userName','duration','coverUrl','photoUrl','photoH265Url','width','height','avgBitrate','manifest','manifestH265',
            'adaptationSet','representation','url','backupUrl','mimeType','fileSize','qualityType','qualityLabel'];
          function mediaValue(value,objects,depth,seen){if(++nodes>12000||depth>24)throw Error('limit');
            if(value===null||typeof value==='boolean'||typeof value==='number')return value;
            if(typeof value==='string'){budget-=value.length;if(value.length>16384||budget<0)throw Error('limit');return value;}
            if(typeof value!=='object')return null;
            var key=value.__ref||(value.type==='id'&&value.id);if(key&&objects&&objects[key])return mediaValue(objects[key],objects,depth+1,seen);
            if(Object.prototype.hasOwnProperty.call(value,'json'))return mediaValue(value.json,objects,depth+1,seen);
            if(seen.indexOf(value)!==-1)throw Error('cycle');seen.push(value);var out=Array.isArray(value)?[]:Object.create(null);
            if(Array.isArray(value)){for(var i=0;i<Math.min(value.length,32);i++)out.push(mediaValue(value[i],objects,depth+1,seen));}
            else for(var i=0;i<mediaKeys.length;i++){var name=mediaKeys[i];if(Object.prototype.hasOwnProperty.call(value,name)){try{out[name]=mediaValue(value[name],objects,depth+1,seen);}catch(e){}}}
            seen.pop();return out;}
          function selectedState(root){try{var objects=root&&root.defaultClient,key='VisionVideoDetailPhoto:'+target,photo=objects&&objects[key];
            if(!photo){photo=root&&root.photo;if(!photo||(photo.id||photo.photoId)!==target)return null;return {photo:mediaValue(photo,null,0,[])};}
            if(photo.id&&photo.id!==target)return null;
            var chosen=Object.create(null);chosen[key]=mediaValue(photo,objects,0,[]);
            var keys=Object.keys(objects);for(var i=0;i<Math.min(keys.length,512);i++){if(keys[i].indexOf('visionVideoDetail')===-1)continue;
              var detail=objects[keys[i]],ref=detail&&detail.photo,photoKey=ref&&(ref.__ref||(ref.type==='id'&&ref.id));if(photoKey!==key)continue;
              var author=detail.author,authorKey=author&&(author.__ref||(author.type==='id'&&author.id));if(authorKey)author=objects[authorKey];
              if(author&&typeof author.name==='string')chosen.detail={photo:{type:'id',id:key},author:{name:author.name.slice(0,16384)}};break;}
            return {defaultClient:chosen};
          }catch(e){return null;}}
          function visible(e){try{var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth&&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0';}catch(x){return false;}}
          try{
            var html=document.documentElement&&document.documentElement.outerHTML;if(html&&html.length<=1048576)result.html=html;else result.htmlOmitted=true;
            function own(name){var d=Object.getOwnPropertyDescriptor(window,name);return d&&Object.prototype.hasOwnProperty.call(d,'value')?d.value:null;}
            state('__APOLLO_STATE__',selectedState(own('__APOLLO_STATE__')));state('INIT_STATE',selectedState(own('INIT_STATE')));
            var roots=[document.getElementById('app'),document.getElementById('__nuxt')];
            for(var i=0;i<roots.length;i++){try{var dollar=String.fromCharCode(36),vue=roots[i]&&roots[i].__vue__,provider=vue&&(vue[dollar+'apolloProvider']||(vue[dollar+'options']&&vue[dollar+'options'].apolloProvider)),client=provider&&provider.defaultClient;
              if(!client&&vue){var apollo=vue[dollar+'apollo'];if(apollo&&typeof apollo.getClient==='function')client=apollo.getClient();}
              if(client&&client.cache&&typeof client.cache.extract==='function')state('ApolloCache',selectedState({defaultClient:client.cache.extract()}));}catch(e){}}
            // The player updates its public videoInfo props after normal verification even when
            // the original global state remains empty. Read only exact-target public media fields.
            var visited=[],pending=[],dollar=String.fromCharCode(36),videos=document.querySelectorAll('video');
            for(var i=0;i<Math.min(videos.length,4);i++){var parent=videos[i];for(var level=0;parent&&level<6;level++,parent=parent.parentElement)if(parent.__vue__)pending.push({vue:parent.__vue__,depth:0});}
            for(var i=0;i<roots.length;i++)if(roots[i]&&roots[i].__vue__)pending.push({vue:roots[i].__vue__,depth:0});
            while(pending.length&&visited.length<64&&result.components.length<2){var next=pending.shift(),vue=next.vue;if(!vue||visited.indexOf(vue)!==-1||next.depth>8)continue;visited.push(vue);
              try{var props=vue[dollar+'props'];if(props)for(var p=0;p<2;p++){var candidate=props[p===0?'videoInfo':'info'];
                if(!candidate||typeof candidate!=='object'||(candidate.id||candidate.photoId)!==target)continue;
                var detail={photo:mediaValue(candidate,null,0,[])};var author=props.author;if(author&&typeof author.name==='string')detail.author={name:author.name.slice(0,16384)};
                var raw=JSON.stringify(detail);if(raw.length<=1048576&&result.components.indexOf(raw)===-1)result.components.push(raw);
              }}catch(e){}
              try{var children=vue[dollar+'children'];if(Array.isArray(children))for(var c=0;c<Math.min(children.length,32);c++)if(pending.length<128)pending.push({vue:children[c],depth:next.depth+1});}catch(e){}
            }
            result.observation={visibleVideos:0,playingVideos:0,httpsVideos:0,blobVideos:0};
            for(var i=0;i<Math.min(videos.length,32);i++){var video=videos[i];if(!visible(video))continue;result.observation.visibleVideos++;
              if(video.readyState>=2&&video.videoWidth>0&&!video.paused)result.observation.playingVideos++;
              var src=video.currentSrc||video.src||'';if(src.indexOf('https:')===0)result.observation.httpsVideos++;else if(src.indexOf('blob:')===0)result.observation.blobVideos++;
            }
            var box=window.__SHIYING_KS_CAPTURE__;if(box&&Array.isArray(box.details))for(var i=0;i<Math.min(box.details.length,4);i++){var raw=box.details[i];try{if(typeof raw==='string'&&raw.length<=1048576&&JSON.parse(raw).photo.id===target)result.details.push(raw);}catch(e){}}
            var alerts=document.querySelectorAll('[role="dialog"],[role="alert"],[class*="captcha"],[id*="captcha"],[class*="verify"],[class*="login-dialog"]');
            for(var i=0;i<Math.min(alerts.length,32);i++){var e=alerts[i];if(!visible(e))continue;var text=(e.innerText||e.textContent||'').slice(0,2048);
              if(/扫码登录|验证码登录|手机号登录|账号登录|登录后才能|请登录后|登录后查看/.test(text)&&!result.blocker)result.blocker='login';else if(/拖动|滑块|验证|验证码/.test(text))result.blocker='verification';}
            var frames=document.querySelectorAll('iframe[src]');for(var i=0;i<Math.min(frames.length,16);i++){var frame=frames[i];if(!visible(frame))continue;
              try{var address=new URL(frame.src,location.href);if(address.protocol==='https:'&&address.hostname==='captcha.zt.kuaishou.com'&&!address.username&&!address.password&&!address.port)result.blocker=result.blocker||'verification';}catch(e){}
            }
          }catch(e){result.captureFailed=true;}
          var json=JSON.stringify(result);if(json.length>4194304)return JSON.stringify({version:'ks-1',pageUrl:result.pageUrl,captureFailed:true});return json;
        })()
    """.trimIndent()
}
