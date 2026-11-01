package io.github.douyinextractor.core.browser

/** Read-only snapshot expression. No native bridge, request interception, cookie read or fetch. */
object BrowserCaptureScript {
    val javascript: String = """
        (function () {
          'use strict';
          var MAX_HTML = 1048576, MAX_STATE = 524288, MAX_NODES = 12000;
          var stateBudget = MAX_STATE, nodes = 0, strings = 0;
          function copy(value, depth, seen) {
            if (++nodes > MAX_NODES || depth > 32) throw new Error('limit');
            if (value === null || typeof value === 'boolean' || typeof value === 'number') return value;
            if (typeof value === 'string') {
              strings += value.length;
              if (strings > MAX_STATE) throw new Error('limit');
              return value;
            }
            if (typeof value !== 'object') return null;
            if (seen.indexOf(value) !== -1) throw new Error('cycle');
            seen.push(value);
            var result = Array.isArray(value) ? [] : Object.create(null);
            var keys = Object.keys(value);
            if (keys.length > MAX_NODES) throw new Error('limit');
            for (var i = 0; i < keys.length; i++) {
              var key = keys[i];
              if (key === '__proto__' || key === 'prototype' || key === 'constructor') continue;
              strings += key.length;
              if (strings > MAX_STATE) throw new Error('limit');
              var descriptor = Object.getOwnPropertyDescriptor(value, key);
              if (descriptor && Object.prototype.hasOwnProperty.call(descriptor, 'value')) {
                result[key] = copy(descriptor.value, depth + 1, seen);
              }
            }
            seen.pop();
            return result;
          }
          function visible(element) {
            var rect = element.getBoundingClientRect(), style = window.getComputedStyle(element);
            return rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0 &&
              rect.top < window.innerHeight && rect.left < window.innerWidth &&
              style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
          }
          function text(element, max) { return element ? (element.textContent || '').slice(0, max) : ''; }
          function blocker(selector, pattern) {
            var elements = document.querySelectorAll(selector);
            for (var i = 0; i < Math.min(elements.length, 24); i++) {
              if (visible(elements[i]) && pattern.test(text(elements[i], 2048))) return true;
            }
            return false;
          }
          function visibleWithAncestors(element) {
            var parent = element;
            for (var level = 0; parent && level < 32; level++, parent = parent.parentElement) {
              if (!visible(parent)) return false;
              var context = (parent.getAttribute('class') || '') + ' ' + (parent.getAttribute('id') || '') +
                ' ' + (parent.getAttribute('data-e2e') || '');
              if (/caption|description|author|comment|video-desc/i.test(context)) return false;
            }
            return !parent;
          }
          function webPlaybackUnavailable() {
            // Only recognize the paired error labels observed on the public share page. Do not
            // classify an app-opening banner, author caption, hidden template or page-wide text.
            var containers = document.querySelectorAll('.special-case-container, [role="alert"], [class*="error"], [class*="Error"], [id*="error"], [id*="Error"], [class*="empty"], [class*="Empty"], [class*="fail"], [class*="Fail"]');
            var budget = 512;
            for (var i = 0; i < Math.min(containers.length, 48) && budget > 0; i++) {
              var container = containers[i];
              if (/^(HTML|BODY|MAIN)$/.test(container.tagName || '') || !visibleWithAncestors(container)) continue;
              var pending = [container], inspected = 0, errorLabel = false, appLabel = false, oversized = false;
              while (pending.length && inspected < 64 && budget > 0) {
                var element = pending.shift(); inspected++; budget--;
                if (!visible(element)) continue;
                var children = element.children || [];
                if (children.length > 32) { oversized = true; break; }
                if (!children.length) {
                  var label = text(element, 65).replace(/\s+/g, '');
                  if (label === '抱歉出错了') errorLabel = true;
                  if (label === '请尝试在抖音内观看') appLabel = true;
                } else {
                  for (var j = 0; j < children.length; j++) pending.push(children[j]);
                }
              }
              if (!oversized && !pending.length && errorLabel && appLabel) return true;
            }
            return false;
          }
          try {
            var states = [], names = ['_ROUTER_DATA','RENDER_DATA','SIGI_STATE','__INITIAL_STATE__',
              '__PRELOADED_STATE__','__SSR_DATA__','__APOLLO_STATE__','__NEXT_DATA__'];
            for (var s = 0; s < names.length; s++) {
              try {
                var descriptor = Object.getOwnPropertyDescriptor(window, names[s]);
                if (!descriptor || !Object.prototype.hasOwnProperty.call(descriptor, 'value')) continue;
                nodes = 0; strings = 0;
                var value = descriptor.value;
                var json = typeof value === 'string' ? value : JSON.stringify(copy(value, 0, []));
                if (typeof json === 'string' && json.length <= stateBudget) {
                  stateBudget -= json.length;
                  states.push({name:names[s],json:json});
                }
              } catch (_) {}
            }
            var canonicalElements = document.querySelectorAll('link[rel="canonical"]');
            var canonical = canonicalElements.length === 1 ? canonicalElements[0].href : '';
            var canonicalConflict = canonicalElements.length > 1;
            var elements = document.querySelectorAll('video'), videos = [];
            for (var i = 0; i < Math.min(elements.length, 32); i++) {
              var v = elements[i], ids = [], parent = v;
              for (var level = 0; parent && level < 6; level++, parent = parent.parentElement) {
                ['data-aweme-id','data-item-id','data-video-id'].forEach(function (key) {
                  var id = parent.getAttribute(key);
                  if (id && ids.indexOf(id) === -1) ids.push(id);
                });
              }
              var mime = v.getAttribute('type') || '';
              if (!mime) {
                var sources = v.querySelectorAll('source');
                for (var j = 0; j < Math.min(sources.length, 16); j++) {
                  if (sources[j].src === v.currentSrc) mime = sources[j].type || '';
                }
              }
              videos.push({currentSrc:v.currentSrc || '',src:v.src || '',poster:v.poster || '',
                width:v.videoWidth,height:v.videoHeight,duration:Number.isFinite(v.duration) ? v.duration : null,
                readyState:v.readyState,visible:visible(v),targetId:ids.length === 1 ? ids[0] : '',
                identityConflict:ids.length > 1,encrypted:!!v.mediaKeys,mimeType:mime});
            }
            var html = document.documentElement ? document.documentElement.outerHTML : '';
            var htmlOmitted = html.length > MAX_HTML;
            if (htmlOmitted) html = '';
            var result = {version:1,pageUrl:window.location.href,readyState:document.readyState,canonical:canonical,
              canonicalConflict:canonicalConflict,html:html,htmlOmitted:htmlOmitted,states:states,
              videos:videos,videoCount:elements.length,title:document.title.slice(0,4096),blocker:{
                verification:blocker('[role="dialog"], [id*="captcha"], [class*="captcha"], [id*="verify"], [class*="verify"]', /验证码|完成验证|安全验证|拖动滑块|点击验证|验证后继续/),
                login:blocker('[role="dialog"], [class*="login-modal"], [class*="login-panel"], [id*="login"]', /登录后(?:查看|观看|继续)|请先登录|登录即可(?:查看|观看)/),
                webPlaybackUnavailable:webPlaybackUnavailable()
              }};
            var serialized = JSON.stringify(result);
            if (serialized.length > 3145728) { result.html = ''; result.htmlOmitted = true; serialized = JSON.stringify(result); }
            return serialized;
          } catch (_) {
            return JSON.stringify({version:1,pageUrl:window.location.href,captureFailed:true});
          }
        })()
    """.trimIndent()
}
