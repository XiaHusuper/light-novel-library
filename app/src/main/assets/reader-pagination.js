/* One page stride for columns, scroll extent, gestures and saved anchors. */
window.ReaderPagination=function(view,article){
  'use strict';let count=1;const track=document.createElement('div');track.className='reader-page-track';article.parentNode.insertBefore(track,article);track.appendChild(article);
  function width(){return Math.max(1,view.getBoundingClientRect().width||view.clientWidth||1);}
  function measure(){
    const stride=width();view.style.setProperty('--column-width',Math.max(100,stride-48)+'px');
    if(!view.classList.contains('mode-page')){count=1;track.style.width='';return;}
    // Use painted content, excluding trailing margins and anonymous blank columns.
    // Give the scroll container an exact whole-page extent including both gutters.
    const nodes=[],walker=document.createTreeWalker(article,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT);let node;
    while((node=walker.nextNode()))if(node.nodeType===3&&node.textContent.trim()||node.nodeType===1&&/^(IMG|HR)$/.test(node.tagName))nodes.push(node);
    const base=article.getBoundingClientRect().left;count=1;
    while(nodes.length){const last=nodes.pop();let rects;if(last.nodeType===3){const text=last.textContent,start=text.search(/\S/),end=text.replace(/\s+$/,'').length,range=document.createRange();range.setStart(last,start);range.setEnd(last,end);rects=Array.from(range.getClientRects());}else rects=Array.from(last.getClientRects());rects=rects.filter(r=>r.width>0&&r.height>0);if(!rects.length)continue;for(const rect of rects)count=Math.max(count,Math.floor((rect.left-base+.75)/stride)+1);break;}
    track.style.width=(count*stride)+'px';
  }
  return {width,measure,pages:()=>count};
};
