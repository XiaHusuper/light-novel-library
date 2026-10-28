/* Toolbars overlay the stable page viewport, so toggling never repaginates text. */
window.ReaderControls=function(options){
  'use strict';
  const reader=options.reader,view=options.viewport,header=document.getElementById('readerToolbar'),footer=document.getElementById('readerFooter'),toggle=document.getElementById('readerToolsToggle');
  let opened=false,touch=null,suppressClickUntil=0;
  const selected=()=>Boolean(String(window.getSelection()||'')),control=target=>target.closest('a,button,input,textarea,select,figure,img,[contenteditable="true"]');
  function set(value,immediate){opened=Boolean(value);if(immediate)reader.classList.add('reader-tools-instant');reader.classList.toggle('reader-tools-open',opened);[header,footer].forEach(element=>{element.setAttribute('aria-hidden',String(!opened));element.querySelectorAll('button').forEach(b=>b.tabIndex=opened?0:-1);});toggle.setAttribute('aria-expanded',String(opened));toggle.setAttribute('aria-label',opened?'收起阅读工具':'显示阅读工具');if(immediate){void reader.offsetHeight;reader.classList.remove('reader-tools-instant');}}
  function flip(){if(options.active())set(!opened);}
  function tap(x){const r=view.getBoundingClientRect(),fraction=(x-r.left)/r.width;if(fraction>.2&&fraction<.8){flip();return;}if(options.mode()!=='page'&&fraction>=0&&fraction<=1)options.turn(fraction<=.2?-1:1,false);}
  toggle.onclick=flip;
  view.addEventListener('touchstart',event=>{touch=null;if(!options.active()||event.touches.length!==1||selected()||control(event.target))return;const t=event.touches[0];touch={id:t.identifier,x:t.clientX,y:t.clientY,time:performance.now(),movement:0};},{passive:true});
  view.addEventListener('touchmove',event=>{if(!touch)return;if(event.touches.length!==1){touch=null;return;}const t=event.touches[0];touch.movement=Math.max(touch.movement,Math.abs(t.clientX-touch.x),Math.abs(t.clientY-touch.y));},{passive:true});
  view.addEventListener('touchend',event=>{const start=touch;touch=null;if(!start)return;suppressClickUntil=performance.now()+700;const t=Array.from(event.changedTouches).find(t=>t.identifier===start.id);if(!t||event.touches.length||!options.active()||selected()||control(event.target))return;if(Math.max(start.movement,Math.abs(t.clientX-start.x),Math.abs(t.clientY-start.y))<=10&&performance.now()-start.time<500)tap(t.clientX);},{passive:true});
  view.addEventListener('touchcancel',()=>{touch=null;suppressClickUntil=performance.now()+700;},{passive:true});
  view.addEventListener('click',event=>{if(!options.active()||performance.now()<suppressClickUntil||event.detail>1||selected()||control(event.target))return;tap(event.clientX);});
  document.addEventListener('keydown',event=>{if(event.key==='Enter'&&options.active()&&!control(event.target)&&!selected()){event.preventDefault();flip();}});
  set(false,true);return {set,close:()=>set(false),get opened(){return opened;}};
};
