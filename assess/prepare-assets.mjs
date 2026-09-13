import { mkdir, copyFile, unlink } from "node:fs/promises";
const dest = new URL("./web/vendor/", import.meta.url);
await mkdir(dest, { recursive: true });
for (const file of ["pdf.min.mjs", "pdf.worker.min.mjs"]) {
  await copyFile(
    new URL(`./node_modules/pdfjs-dist/build/${file}`, import.meta.url),
    new URL(file.replace(".mjs", ".js"), dest),
  );
  // Remove only earlier generated copies with the obsolete extension.
  await unlink(new URL(file,dest)).catch(e=>{if(e.code!=='ENOENT')throw e;});
}
await copyFile(
  new URL("./node_modules/pdfjs-dist/LICENSE", import.meta.url),
  new URL("PDFJS-LICENSE.txt", dest),
);
