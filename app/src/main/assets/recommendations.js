(function(root){
  'use strict';
  const SHA_K=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
  const rotate=(x,n)=>(x>>>n)|(x<<(32-n));
  // SHA-256 supplies the same stable account/day/batch ordering as the website.
  function seed(value){
    const text=unescape(encodeURIComponent(String(value))),bytes=Array.from(text,c=>c.charCodeAt(0)),bits=bytes.length*8;
    bytes.push(128);while(bytes.length%64!==56)bytes.push(0);
    for(let i=7;i>=0;i--)bytes.push(i<4?(bits>>>i*8)&255:0);
    const h=[0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19],w=new Int32Array(64);
    for(let start=0;start<bytes.length;start+=64){
      for(let i=0;i<16;i++){const j=start+i*4;w[i]=(bytes[j]<<24)|(bytes[j+1]<<16)|(bytes[j+2]<<8)|bytes[j+3];}
      for(let i=16;i<64;i++){const a=w[i-15],b=w[i-2];w[i]=(w[i-16]+(rotate(a,7)^rotate(a,18)^(a>>>3))+w[i-7]+(rotate(b,17)^rotate(b,19)^(b>>>10)))|0;}
      let [a,b,c,d,e,f,g,z]=h;
      for(let i=0;i<64;i++){const t1=(z+(rotate(e,6)^rotate(e,11)^rotate(e,25))+((e&f)^(~e&g))+SHA_K[i]+w[i])|0,t2=((rotate(a,2)^rotate(a,13)^rotate(a,22))+((a&b)^(a&c)^(b&c)))|0;z=g;g=f;f=e;e=(d+t1)|0;d=c;c=b;b=a;a=(t1+t2)|0;}
      [a,b,c,d,e,f,g,z].forEach((n,i)=>h[i]=(h[i]+n)|0);
    }
    return ((h[0]>>>0)*4294967296+(h[1]>>>0))/18446744073709551615;
  }
  function select(books,preference,shelf,history,learning,excluded,options){
    const L=root.ReaderLibrary,P=root.ReaderProfile;
    preference=preference||{};options=options||{};shelf=shelf||[];history=history||[];
    const limit=Math.max(1,Math.min(20,options.limit||20)),avoid=new Set(preference.avoid||[]),feedback=preference.feedback||{},unique=new Map();
    history.concat(shelf,books).forEach(b=>unique.set(String(b.id),b));
    const explicit=L.features((preference.likes||[]).filter(t=>!avoid.has(t))),profile={},records=[];
    Object.keys(explicit).forEach(t=>profile[t]=6*explicit[t]);
    let evidence=Object.keys(explicit).length?6:0;
    unique.forEach(book=>{const id=String(book.id),score=feedback[id]==='hide'?0:Math.min(10,(shelf.some(b=>String(b.id)===id&&b.favorite===true)?4:0)+(feedback[id]==='like'?6:0));if(score){records.push({id,score,hidden:false});evidence+=score;const f=L.features(L.tags(book));Object.keys(f).forEach(t=>{if(!avoid.has(t))profile[t]=(profile[t]||0)+score*f[t];});}});
    let signals=records;
    if(learning&&P){const result=P.calculate(Array.from(unique.values()),preference,shelf,learning,options.now);Object.keys(profile).forEach(t=>delete profile[t]);Object.assign(profile,result.vector);evidence=result.evidence;signals=result.records;}
    const confidence=Math.min(1,evidence/6),personalized=confidence>=.25&&Object.keys(profile).some(t=>profile[t]>0),meaningful=signals.filter(b=>!b.hidden&&b.score>=1).length,rate=personalized?(meaningful<3?.2:.1):0;
    const observed=new Set(history.concat(shelf).map(b=>String(b.id)));signals.filter(b=>!b.hidden&&b.score>0).forEach(b=>observed.add(String(b.id)));
    Object.keys(feedback).filter(id=>feedback[id]==='like').forEach(id=>observed.add(id));
    const known=new Set(preference.likes||[]);unique.forEach(book=>{if(observed.has(String(book.id)))L.tags(book).forEach(t=>known.add(t));});
    const shown=(options.shown||[]).map(String),skipped=new Set(shown),identity=options.identity||'guest',day=new Date((options.now||Date.now())+8*3600000).toISOString().slice(0,10),batch=options.batch||0;
    const random=(id,salt='')=>seed(identity+':'+day+':'+batch+':'+id+':'+salt);
    const unit=f=>{const length=Math.sqrt(Object.values(f).reduce((sum,n)=>sum+n*n,0));return Object.fromEntries(Object.entries(f).map(([t,n])=>[t,length?n/length:0]));};
    const candidates=Array.from(unique.values()).filter(b=>b.source!=='local'&&!L.inShelf(b,(excluded||[]).concat(shelf))&&feedback[String(b.id)]!=='hide'&&!L.tags(b).some(t=>avoid.has(t))).map(book=>{
      const id=String(book.id),f=L.features(L.tags(book)),content=L.features(L.tags(book).filter(t=>['daily','fantasy','dark'].includes(root.ReaderTaxonomy.groups.find(g=>g.tags.includes(t))?.id))),balance=unit(Object.keys(content).length?content:Object.keys(f).length?f:{untagged:1}),match=L.cosine(profile,f),novelty=Object.keys(f).reduce((sum,t)=>sum+(known.has(t)?0:f[t]),0),familiarity=Object.keys(f).reduce((sum,t)=>sum+(known.has(t)?f[t]:0),0);
      return{book,id,f,balance,match,novelty,familiarity,rank:confidence*match+(1-confidence)*random(id),fresh:!skipped.has(id)};
    });
    const unseen=candidates.filter(c=>c.fresh).length,reset=Boolean(options.allowReset&&candidates.length&&unseen===0);
    if(reset){const recent=new Set(shown.slice(-limit));candidates.forEach(c=>c.fresh=!recent.has(c.id));}
    const desired=Math.min(limit,reset?candidates.length:unseen),target=Math.floor(desired*rate+1e-9),used=new Set(),main=[],explorers=[];
    const redundancy=(candidate,selected)=>selected.reduce((value,c)=>Math.max(value,L.cosine(candidate.f,c.f)),0);
    function choose(pool,selected,explore){
      if(pool.some(c=>c.fresh))pool=pool.filter(c=>c.fresh);
      const familiar=pool.filter(c=>c.familiarity>0);if(!explore&&personalized&&known.size&&familiar.length)pool=familiar;
      let best=null,bestValue=-Infinity;
      pool.forEach(c=>{
        let value;if(!personalized){const loads={};selected.forEach(other=>Object.keys(other.balance).forEach(t=>loads[t]=(loads[t]||0)+other.balance[t]));value=-Math.round(Object.keys(c.balance).reduce((sum,t)=>sum+(loads[t]||0)*c.balance[t],0)*1e10)/1e10;}
        else value=explore?.75*c.novelty+.25*random(c.id,'explore')-.2*redundancy(c,selected):c.rank-(.08+.12*(1-confidence))*redundancy(c,selected);
        if(best===null||value>bestValue||value===bestValue&&(!personalized&&c.rank>best.rank||((personalized||c.rank===best.rank)&&Number(c.id)<Number(best.id)))){best=c;bestValue=value;}
      });return best;
    }
    const hasFresh=candidates.some(c=>c.fresh);let pool=candidates.filter(c=>!observed.has(c.id)&&c.novelty>0&&(!hasFresh||c.fresh));
    for(let i=0;i<Math.min(target,pool.length+explorers.length);i++){const c=choose(pool,explorers,true);explorers.push(c);used.add(c.id);pool.splice(pool.indexOf(c),1);}
    pool=candidates.filter(c=>!used.has(c.id));
    for(let i=0;i<desired-explorers.length;i++){const c=choose(pool,main,false);main.push(c);pool.splice(pool.indexOf(c),1);}
    const positions=new Set(explorers.map((_,i)=>Math.floor((i+1)*desired/explorers.length)-1));let mainIndex=0,exploreIndex=0;
    const items=Array.from({length:desired},(_,i)=>{const exploring=positions.has(i),c=exploring?explorers[exploreIndex++]:main[mainIndex++],matching=Object.keys(c.f).filter(t=>profile[t]>0).sort((a,b)=>profile[b]*c.f[b]-profile[a]*c.f[a]).slice(0,2),newTags=Object.keys(c.f).filter(t=>!known.has(t)).sort((a,b)=>c.f[b]-c.f[a]).slice(0,2);return Object.assign({},c.book,{recommendationKind:exploring?'explore':personalized&&c.match>0?'personalized':'discovery',recommendationReason:exploring?'换个口味 · '+newTags.join('、'):personalized&&matching.length?'偏好匹配 · '+matching.join('、'):'题材发现',recommendationScore:Math.round(c.match*100)});});
    return{books:items,rotationReset:reset,remainingUnseen:Math.max(0,(reset?candidates.length:unseen)-items.length),explorationCount:explorers.length,explorationRate:rate,confidence,meaningfulBooks:meaningful};
  }
  root.ReaderRecommendation={select,seed};
})(typeof window!=='undefined'?window:globalThis);
