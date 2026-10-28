/* Stable reading anchors. Persists hashes and image dimensions, never chapter text. */
window.ReaderLocation = function(view,article){
  'use strict';
  let elements=[],held=null,planned=null,hints=new Map();
  const clamp=(v,a,b)=>Math.max(a,Math.min(b,Number(v)||0));
  function hash(text){let a=2166136261,b=5381;for(let i=0;i<text.length;i++){a=Math.imul(a^text.charCodeAt(i),16777619);b=Math.imul(b,33)^text.charCodeAt(i);}return (a>>>0).toString(16).padStart(8,'0')+(b>>>0).toString(16).padStart(8,'0');}
  const imageId=url=>hash(String(url||'').replace(/^http:\/\//,'https://'));
  function index(){const counts={};elements=Array.from(article.querySelectorAll('figure,p,h1,h2,h3,h4,h5,h6,li,pre,blockquote')).filter(e=>!e.closest('figcaption')&&(e.tagName==='FIGURE'||!e.querySelector('figure,p,li,h1,h2,h3,h4,h5,h6')));elements.forEach((e,i)=>{const img=e.tagName==='FIGURE'&&e.querySelector('img'),base=(img?'i:'+imageId(img.dataset.src):'t:'+hash(e.textContent.replace(/\s+/g,' ').trim().slice(0,160)));const n=counts[base]||0;counts[base]=n+1;e.dataset.readerAnchor=base+':'+n;e.dataset.readerIndex=i;});}
  function capture(){
    if(held)return Object.assign({},held);if(!elements.length)return null;
    const bounds=view.getBoundingClientRect(),paged=view.classList.contains('mode-page'),at=paged?0:.25,y=bounds.top+bounds.height*at;
    let choice=null,best=Infinity;
    elements.forEach((element,index)=>Array.from(element.getClientRects()).forEach((r,fragment)=>{
      if(r.width<=0||r.height<=0)return;if(paged&&(r.right<=bounds.left+1||r.left>=bounds.right-1||r.bottom<=bounds.top||r.top>=bounds.bottom))return;
      const distance=paged?Math.max(0,r.top-bounds.top):r.top<=y&&r.bottom>y?0:r.top>y?r.top-y:100000+(y-r.bottom);
      if(distance<best){best=distance;choice={element,index,r,fragment};}
    }));
    if(!choice)return null;const r=choice.r;return {key:choice.element.dataset.readerAnchor,index:choice.index,fragment:choice.fragment,fraction:paged?0:clamp((y-r.top)/r.height,0,1),at,headroom:paged?0:clamp((r.top-y)/Math.max(1,bounds.height),0,1)};
  }
  function fromRange(range){if(!range)return capture();const node=range.startContainer,index=elements.findIndex(e=>e===node||e.contains(node));if(index<0)return capture();const element=elements[index],rect=Array.from(range.getClientRects()).find(r=>r.width>0&&r.height>0),rects=Array.from(element.getClientRects());if(!rect||!rects.length)return capture();let fragment=rects.findIndex(r=>rect.left>=r.left-1&&rect.left<r.right&&rect.top>=r.top-1&&rect.top<r.bottom);if(fragment<0)fragment=0;return {key:element.dataset.readerAnchor,index,fragment,fraction:view.classList.contains('mode-page')?0:clamp((rect.top-rects[fragment].top)/rects[fragment].height,0,1),at:view.classList.contains('mode-page')?0:.25,headroom:0};}
  function apply(location){
    if(!location||!elements.length)return false;const element=elements.find(e=>e.dataset.readerAnchor===location.key)||elements[clamp(location.index,0,elements.length-1)];if(!element)return false;
    const list=Array.from(element.getClientRects()),r=list[clamp(location.fragment,0,Math.max(0,list.length-1))];if(!r)return false;const bounds=view.getBoundingClientRect();
    if(view.classList.contains('mode-page')){const stride=Math.max(1,bounds.width),left=view.scrollLeft+r.left-bounds.left;view.scrollLeft=Math.max(0,Math.floor((left+1)/stride)*stride);}
    else view.scrollTop+=r.top+clamp(location.fraction,0,1)*r.height-bounds.top-(clamp(location.at,0,1)+clamp(location.headroom,0,1))*bounds.height;
    planned={top:view.scrollTop,left:view.scrollLeft};return true;
  }
  function restore(location){if(!apply(location))return false;held=Object.assign({},location);return true;}
  function release(){held=null;}
  function mutate(change){const anchor=held||capture();change();if(anchor)apply(anchor);requestAnimationFrame(()=>{if(held)apply(held);});}
  function setHints(values){hints=new Map();(values||[]).slice(0,80).forEach(item=>{if(Array.isArray(item)&&/^[a-f0-9]{16}$/.test(item[0])&&Number(item[1])>0)hints.set(item[0],{ratio:Number(item[1]),width:Number(item[2])||0});});}
  function note(url,width,height){if(width<=0||height<=0)return;const id=imageId(url);hints.delete(id);hints.set(id,{ratio:height/width,width});while(hints.size>80)hints.delete(hints.keys().next().value);}
  function reserve(figure,url){const hint=hints.get(imageId(url));if(!hint){figure.style.minHeight='220px';return;}let height=Math.min(view.clientWidth-48,hint.width||view.clientWidth-48)*hint.ratio;if(view.classList.contains('mode-page'))height=Math.min(height,window.innerHeight*.65);figure.style.minHeight=(Math.max(0,height)+28)+'px';}
  function reset(values){held=null;planned=null;elements=[];setHints(values);}
  return {index,capture,fromRange,restore,release,mutate,reserve,note,reset,apply,onScroll:()=>{if(held&&planned&&(Math.abs(view.scrollTop-planned.top)>2||Math.abs(view.scrollLeft-planned.left)>2))release();},dimensions:url=>{const h=hints.get(imageId(url));return h?{width:h.width,height:h.width*h.ratio}:null;},getHints:()=>Array.from(hints,([id,v])=>[id,Number(v.ratio.toFixed(6)),v.width])};
};
