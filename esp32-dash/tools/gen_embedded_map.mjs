import fs from 'fs';import path from 'path';import {createRequire} from 'module';
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs';
const require=createRequire(import.meta.url);const {createCanvas}=require('@napi-rs/canvas');
const [PDF,OUT]=process.argv.slice(2);const CENTRE={lat:-33.899,lon:151.234};const M=111319.491;
const LEVELS=[{name:"17m",mPx:50/3,size:1000},{name:"33m",mPx:100/3,size:1000},{name:"67m",mPx:200/3,size:500}];
const BB=[340.156887518,85.0392,2239.365687518,2097.6336];
const C={sw:[-34.53352,149.93538],nw:[-32.93485,149.99175],ne:[-32.96515,151.78254],se:[-34.5657,151.75969]};
const fwd=(u,v)=>[(1-u)*(1-v)*C.sw[0]+(1-u)*v*C.nw[0]+u*v*C.ne[0]+u*(1-v)*C.se[0],(1-u)*(1-v)*C.sw[1]+(1-u)*v*C.nw[1]+u*v*C.ne[1]+u*(1-v)*C.se[1]];
function inv(lat,lon,g=[.5,.5]){let[u,v]=g;for(let i=0;i<6;i++){const[la,lo]=fwd(u,v),[la1,lo1]=fwd(u+1e-3,v),[la2,lo2]=fwd(u,v+1e-3);const a=(lo1-lo)/1e-3,b=(lo2-lo)/1e-3,c=(la1-la)/1e-3,d=(la2-la)/1e-3,det=a*d-b*c,x=lon-lo,y=lat-la;u+=(d*x-b*y)/det;v+=(-c*x+a*y)/det}return[u,v]}
const [a0,b0]=fwd(0,.5),[c0,d0]=fwd(1,.5);const mPerPt=Math.hypot((d0-b0)*111320*Math.cos(33.8*Math.PI/180),(c0-a0)*110574)/(BB[2]-BB[0]);
const doc=await pdfjs.getDocument({data:new Uint8Array(fs.readFileSync(PDF)),useSystemFonts:true,verbosity:0,standardFontDataUrl:path.resolve('node_modules/pdfjs-dist/standard_fonts')+'/',cMapUrl:path.resolve('node_modules/pdfjs-dist/cmaps')+'/',cMapPacked:true}).promise;
const page=await doc.getPage(1);const pageH=page.view[3];const cosC=Math.cos(CENTRE.lat*Math.PI/180);
for(const {name,mPx,size} of LEVELS){const t0=Date.now();
 const SS=2,MARGIN=1.15,srcSize=Math.ceil(size*SS*MARGIN);const scale=mPerPt/mPx*SS;
 const[uc,vc]=inv(CENTRE.lat,CENTRE.lon);const px=BB[0]+uc*(BB[2]-BB[0]),py=BB[1]+vc*(BB[3]-BB[1]);
 const ox=-(px*scale-srcSize/2),oy=-((pageH-py)*scale-srcSize/2);
 const cv=createCanvas(srcSize,srcSize);const ctx=cv.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,srcSize,srcSize);
 await page.render({canvasContext:ctx,viewport:page.getViewport({scale,offsetX:ox,offsetY:oy})}).promise;
 const src=ctx.getImageData(0,0,srcSize,srcSize).data;
 const buf=Buffer.alloc(size*size*2);const pv=createCanvas(size,size);const pc=pv.getContext('2d');const img=pc.createImageData(size,size);
 let g=[uc,vc];
 for(let y=0;y<size;y++){for(let x=0;x<size;x++){
  const east=(x+.5-size/2)*mPx,north=(size/2-y-.5)*mPx;
  const lon=CENTRE.lon+east/(M*cosC);
  const lat2=CENTRE.lat+north/M;
  g=inv(lat2,lon,g);
  const pdfX=BB[0]+g[0]*(BB[2]-BB[0]),pdfY=BB[1]+g[1]*(BB[3]-BB[1]);
  const sx=pdfX*scale+ox-.5,sy=(pageH-pdfY)*scale+oy-.5;
  const ix=Math.floor(sx),iy=Math.floor(sy),fx=sx-ix,fy=sy-iy;let r=255,gr=255,b=255;
  if(ix>=0&&iy>=0&&ix+1<srcSize&&iy+1<srcSize){const s=(i,j,c)=>src[(j*srcSize+i)*4+c];const bl=c=>(1-fx)*(1-fy)*s(ix,iy,c)+fx*(1-fy)*s(ix+1,iy,c)+(1-fx)*fy*s(ix,iy+1,c)+fx*fy*s(ix+1,iy+1,c);r=bl(0);gr=bl(1);b=bl(2)}
  const o=(y*size+x);const c565=((r&0xF8)<<8)|((gr&0xFC)<<3)|(b>>3);buf[o*2]=c565>>8;buf[o*2+1]=c565&255;
  img.data[o*4]=r;img.data[o*4+1]=gr;img.data[o*4+2]=b;img.data[o*4+3]=255}}
 fs.writeFileSync(path.join(OUT,`map_${name}.bin`),buf);pc.putImageData(img,0,0);fs.writeFileSync(`check_${name}.png`,pv.toBuffer('image/png'));
 console.log(`${mPx} m/px ${size}x${size} covers ${(size*mPx/1000)} km, ${(buf.length/1e6).toFixed(2)} MB, ${((Date.now()-t0)/1000).toFixed(0)} s`)}
process.exit(0);
