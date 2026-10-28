/* Finger-following page movement; edge taps deliberately remain immediate. */
window.ReaderPageMotion = function (options) {
  'use strict';
  const view=options.viewport,stage=options.stage,neighbor=options.neighbor;
  let touch=null,busy=false,offset=0,direction=0,frame=0,sequence=0,resolveAnimation=null,suppressUntil=0,suppressImageUntil=0;
  const width=()=>options.width?options.width():Math.max(1,view.getBoundingClientRect().width);
  const selected=()=>Boolean(String(window.getSelection()||''));
  const control=target=>Boolean(target.closest('a,button,input,textarea,select,[contenteditable="true"]'));
  const reduced=()=>window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  function paint(value){offset=value;view.style.transform='translateX('+value+'px)';neighbor.style.transform='translateX('+(value+direction*width())+'px)';}
  function preview(dir){
    const chapter=options.neighborFor(dir);direction=dir;
    neighbor.textContent='';neighbor.classList.toggle('hidden',!chapter);
    if(chapter){const title=document.createElement('div'),hint=document.createElement('p');title.className='chapter-turn-cue';title.textContent=dir>0?'下一章 · 开篇':'上一章 · 末页';hint.textContent=dir>0?'松开后继续阅读':'松开后返回上一章末页';neighbor.append(title,hint);}
    paint(offset);return Boolean(chapter);
  }
  function stop(){sequence++;cancelAnimationFrame(frame);if(resolveAnimation){const done=resolveAnimation;resolveAnimation=null;done(false);}busy=false;}
  function animate(left,endOffset,smooth){
    stop();const token=sequence,from=view.scrollLeft,startOffset=offset,start=performance.now(),duration=smooth&&!reduced()?220:0;
    if(!duration){view.scrollLeft=left;paint(endOffset);if(!endOffset)neighbor.classList.add('hidden');return Promise.resolve(true);}
    busy=true;stage.classList.add('turning');
    return new Promise(resolve=>{resolveAnimation=resolve;
      function tick(now){if(token!==sequence)return;const t=Math.min(1,(now-start)/duration),ease=1-Math.pow(1-t,3);view.scrollLeft=from+(left-from)*ease;paint(startOffset+(endOffset-startOffset)*ease);
        if(t<1)frame=requestAnimationFrame(tick);else{busy=false;resolveAnimation=null;stage.classList.remove('turning');if(!endOffset)neighbor.classList.add('hidden');resolve(true);}}
      frame=requestAnimationFrame(tick);
    });
  }
  function reset(){const original=touch&&touch.left,wasMoving=busy;touch=null;stop();stage.classList.remove('dragging','turning');if(original!=null)view.scrollLeft=original;else if(wasMoving&&view.classList.contains('mode-page'))view.scrollLeft=Math.round(view.scrollLeft/Math.max(1,width()))*width();offset=0;direction=0;view.style.transform='';neighbor.style.transform='';neighbor.classList.add('hidden');}
  function cancel(){const start=touch;touch=null;stage.classList.remove('dragging');suppressUntil=performance.now()+700;if(start&&start.dragging){suppressImageUntil=suppressUntil;animate(start.left,0,true).then(ok=>{if(ok)options.onPosition();});}else{paint(0);neighbor.classList.add('hidden');}}
  function edge(x){const r=view.getBoundingClientRect(),fraction=(x-r.left)/r.width;if(fraction>=0&&fraction<=.2){options.turn(-1,false);return true;}if(fraction>=.8&&fraction<=1){options.turn(1,false);return true;}return false;}
  view.addEventListener('touchstart',event=>{
    if(!options.active()||busy||event.touches.length!==1||selected()||control(event.target))return;
    const t=event.touches[0],page=Math.round(view.scrollLeft/width()),left=page*width();
    view.scrollLeft=left;touch={id:t.identifier,x:t.clientX,y:t.clientY,time:performance.now(),page,left,movement:0,dragging:false};
  },{passive:true});
  view.addEventListener('touchmove',event=>{
    if(!touch)return;if(event.touches.length!==1||selected()||!options.active()){cancel();return;}
    const t=event.touches[0];if(t.identifier!==touch.id){cancel();return;}
    const dx=t.clientX-touch.x,dy=t.clientY-touch.y;touch.movement=Math.max(touch.movement,Math.abs(dx),Math.abs(dy));
    if(!touch.dragging&&Math.abs(dy)>10&&Math.abs(dy)>Math.abs(dx)*1.2){cancel();return;}
    if(!touch.dragging&&Math.abs(dx)>=4&&Math.abs(dx)>Math.abs(dy)*1.2)touch.dragging=true;if(!touch.dragging)return;
    event.preventDefault();stage.classList.add('dragging');
    const stride=width(),min=Math.max(0,touch.page-1)*stride,max=Math.min(options.pages()-1,touch.page+1)*stride,desired=touch.left-dx,bounded=Math.max(min,Math.min(max,desired));view.scrollLeft=bounded;
    const overflow=bounded-desired,dir=overflow<0?1:overflow>0?-1:0;
    if(dir){const has=preview(dir);paint(Math.max(-stride,Math.min(stride,overflow*(has?1:.22))));}else{paint(0);neighbor.classList.add('hidden');}
  },{passive:false});
  view.addEventListener('touchend',event=>{
    const start=touch;touch=null;stage.classList.remove('dragging');if(!start)return;
    suppressUntil=performance.now()+700;const t=Array.from(event.changedTouches).find(t=>t.identifier===start.id);
    if(!t||event.touches.length||selected()){animate(start.left,0,true);return;}
    const dx=t.clientX-start.x,dy=t.clientY-start.y,movement=Math.max(start.movement,Math.abs(dx),Math.abs(dy));
    if(start.dragging){suppressImageUntil=suppressUntil;if(Math.abs(dx)>width()*.025)options.turn(dx<0?1:-1,true,start.page);else animate(start.left,0,true).then(ok=>{if(ok)options.onPosition();});return;}
    if(movement<=10&&performance.now()-start.time<500&&!event.target.closest('figure,img'))edge(t.clientX);
  },{passive:true});
  view.addEventListener('touchcancel',cancel,{passive:true});
  view.addEventListener('click',event=>{if(!options.active()||busy||performance.now()<suppressUntil||event.detail>1||selected()||control(event.target)||event.target.closest('figure,img'))return;edge(event.clientX);});
  return {reset,scroll:(left,smooth)=>animate(left,0,smooth),cross:async(dir,smooth)=>{preview(dir);return animate(view.scrollLeft,-dir*width(),smooth);},rebound:()=>animate(view.scrollLeft,0,true),get busy(){return busy;},get dragging(){return Boolean(touch&&touch.dragging);},get suppressImages(){return performance.now()<suppressImageUntil;}};
};
