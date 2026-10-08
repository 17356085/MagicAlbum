import fs from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'
import pngToIco from 'png-to-ico'

async function main() {
  const root = path.resolve('.')
  const svgPath = path.join(root, 'public', 'favicon.svg')
  const outIco = path.join(root, 'public', 'favicon.ico')

  try {
    const svgBuffer = await fs.readFile(svgPath)
    const sizes = [16, 32, 48]
    const pngBuffers = []
    for (const size of sizes) {
      const png = await sharp(svgBuffer).resize(size, size, { fit: 'contain', background: { r: 255, g: 255, b: 255, alpha: 0 } }).png().toBuffer()
      pngBuffers.push(png)
    }
    const icoBuffer = await pngToIco(pngBuffers)
    await fs.writeFile(outIco, icoBuffer)
    console.log('Generated', outIco)
  } catch (e) {
    console.error('Failed to generate favicon.ico:', e)
    process.exitCode = 1
  }
}

main()