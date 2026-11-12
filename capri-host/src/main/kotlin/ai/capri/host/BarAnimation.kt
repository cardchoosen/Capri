package ai.capri.host

import org.json.JSONObject
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.roundToInt

/** Renders numeric bar-chart frames without executing workspace code. */
internal fun renderBarAnimation(args: JSONObject): ByteArray {
    val frames = jsonObjects(args.getJSONArray("frames"))
    val values = frames.map { frame ->
        val bars = frame.getJSONArray("values")
        (0 until bars.length()).map { bars.getInt(it) }
    }
    val count = values.first().size
    if (values.any { it.size != count } ||
        frames.any { it.has("highlight") && it.getInt("highlight") !in 0 until count })
        throw WorkspaceFailure("invalid_frames", "Keep the number of bars consistent and use a valid zero-based highlight.")
    val maxValue = values.maxOf { it.max() }.coerceAtLeast(1)
    val delay = args.optInt("delayMs", 250) / 10
    val output = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    try {
        MemoryCacheImageOutputStream(output).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            for ((index, bars) in values.withIndex()) {
                val frame = frames[index]
                val image = drawBars(bars, maxValue, frame.optInt("highlight", -1), frame.optString("caption"))
                val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), writer.defaultWriteParam)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                val control = root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                control.setAttribute("delayTime", delay.toString())
                if (index == 0) {
                    val extensions = IIOMetadataNode("ApplicationExtensions")
                    val loop = IIOMetadataNode("ApplicationExtension")
                    loop.setAttribute("applicationID", "NETSCAPE")
                    loop.setAttribute("authenticationCode", "2.0")
                    loop.userObject = byteArrayOf(1, 0, 0)
                    extensions.appendChild(loop)
                    root.appendChild(extensions)
                }
                metadata.setFromTree(format, root)
                writer.writeToSequence(javax.imageio.IIOImage(image, null, metadata), writer.defaultWriteParam)
                if (stream.streamPosition > WorkspaceTools.maxBytes)
                    throw WorkspaceFailure("animation_too_large", "Use fewer frames to keep the GIF under 1 MiB.")
            }
            writer.endWriteSequence()
        }
        return output.toByteArray()
    } finally {
        writer.dispose()
    }
}

private fun drawBars(values: List<Int>, maxValue: Int, highlight: Int, caption: String): BufferedImage {
    val image = BufferedImage(480, 270, BufferedImage.TYPE_BYTE_INDEXED)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color(10, 15, 22)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.color = Color(95, 110, 132)
        graphics.drawLine(20, 230, 460, 230)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val slot = 440.0 / values.size
        for ((index, value) in values.withIndex()) {
            val left = (20 + index * slot).roundToInt()
            val width = (slot - 4).roundToInt()
            val height = (176.0 * value / maxValue).roundToInt().coerceAtLeast(1)
            graphics.color = if (index == highlight) Color(255, 178, 55) else Color(62, 188, 167)
            graphics.fillRect(left + 2, 230 - height, width, height)
            if (values.size <= 16) {
                val label = value.toString()
                graphics.color = Color.WHITE
                graphics.drawString(label, left + (slot.toInt() - graphics.fontMetrics.stringWidth(label)) / 2, 247)
            }
        }
        graphics.color = Color.WHITE
        graphics.drawString(caption, 20, 24)
    } finally {
        graphics.dispose()
    }
    return image
}
