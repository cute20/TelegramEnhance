export const GROUP_BYTES=8*1024*1024*1024
export const byteSplitCounts=[6,8,7,5,5,6,7,8,9,5]
/** Large sizes are metadata only; this fixture does not create or read media files. */
export function byteSplitGroups(){
  let serial=0
  return byteSplitCounts.map((count,index)=>({number:index+1,mediaType:'VIDEO',mode:'ALBUM',files:Array.from({length:count},()=>{
    const name=`video-${String(++serial).padStart(3,'0')}.mp4`
    return{relativePath:name,fileName:name,mediaType:'VIDEO',caption:name,size:Math.floor(GROUP_BYTES*15/16/count)}
  })}))
}
