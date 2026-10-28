(function(root){
  'use strict';
  root.ReaderLocalOrderEditor=function(options){
    const $=id=>document.getElementById(id),screen=$('localOrderScreen'),names={main:'正篇',side:'外传',web:'Web'};
    let book=null,automatic=null,groups=[],mode='volume',selection='',busy=false;
    const esc=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    function close(){if(busy)return true;screen.classList.add('hidden');book=null;options.closed();return true;}
    function render(){
      const content=$('localOrderContent'),scroll=content.scrollTop;
      document.querySelectorAll('[data-order-mode]').forEach(b=>b.classList.toggle('active',b.dataset.orderMode===mode));
      const choices=mode==='volume'?['main','side','web'].filter(e=>groups.some(g=>g.edition===e)).map(e=>({key:e,name:names[e]})):groups.map(g=>({key:g.key,name:names[g.edition]+' · '+g.name}));
      if(!choices.some(c=>c.key===selection))selection=choices.length?choices[0].key:'';
      $('localOrderSelect').innerHTML=choices.map(c=>'<option value="'+esc(encodeURIComponent(c.key))+'">'+esc(c.name)+'</option>').join('');$('localOrderSelect').value=encodeURIComponent(selection);
      const selected=mode==='volume'?groups.filter(g=>g.edition===selection):((groups.find(g=>g.key===selection)||{}).items||[]);
      $('localOrderList').innerHTML=selected.map((item,i)=>'<div class="local-order-row"><span class="local-order-number">'+(i+1)+'</span><div><strong>'+esc(mode==='volume'?item.name:item.title)+'</strong>'+(mode==='volume'?'<small>'+item.items.length+' 章</small>':'')+'</div><button data-order-move="'+i+'" data-direction="-1" aria-label="上移'+esc(mode==='volume'?item.name:item.title)+'" '+(i===0?'disabled':'')+'>↑</button><button data-order-move="'+i+'" data-direction="1" aria-label="下移'+esc(mode==='volume'?item.name:item.title)+'" '+(i===selected.length-1?'disabled':'')+'>↓</button></div>').join('');
      content.scrollTop=scroll;$('localOrderSave').disabled=busy;$('localOrderReset').disabled=busy;$('localOrderSelect').disabled=busy;
    }
    $('localOrderClose').onclick=close;
    document.querySelectorAll('[data-order-mode]').forEach(b=>b.onclick=()=>{if(busy)return;mode=b.dataset.orderMode;selection='';render();});
    $('localOrderSelect').onchange=()=>{selection=decodeURIComponent($('localOrderSelect').value);render();};
    $('localOrderList').onclick=event=>{const button=event.target.closest('[data-order-move]');if(!button||busy)return;const from=Number(button.dataset.orderMove),to=from+Number(button.dataset.direction);if(mode==='volume'){const selected=groups.filter(g=>g.edition===selection);if(to<0||to>=selected.length)return;const a=groups.indexOf(selected[from]),b=groups.indexOf(selected[to]);[groups[a],groups[b]]=[groups[b],groups[a]];}else{const group=groups.find(g=>g.key===selection);if(!group||to<0||to>=group.items.length)return;[group.items[from],group.items[to]]=[group.items[to],group.items[from]];}render();};
    $('localOrderReset').onclick=()=>{if(busy)return;groups=options.library.chapterGroups(automatic.chapters);selection='';render();options.toast('已恢复自动顺序，点保存后生效');};
    $('localOrderSave').onclick=async()=>{if(busy||!book)return;busy=true;render();try{const keys=groups.reduce((a,g)=>a.concat(g.items.map(c=>c.key)),[]),original=automatic.chapters.map(c=>c.key);await options.save(book,keys,JSON.stringify(keys)===JSON.stringify(original));busy=false;close();options.toast('目录顺序已保存');}catch(error){busy=false;render();options.toast(error.message);}};
    return {open(value,auto){book=value;automatic=auto||value;groups=options.library.chapterGroups(value.chapters);mode='volume';selection='';$('localOrderTitle').textContent=value.title;screen.classList.remove('hidden');$('localOrderContent').scrollTop=0;render();},close};
  };
})(window);
