package io.aexp.bucketlist.examples.prlifetime

import de.erichseifert.gral.data.statistics.Statistics
import de.erichseifert.gral.io.plots.DrawableWriterFactory
import de.erichseifert.gral.plots.BoxPlot
import de.erichseifert.gral.plots.XYPlot
import de.erichseifert.gral.util.Insets2D
import io.aexp.bucketlist.examples.getTsvBoxWhiskerDataSource
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors
import java.util.stream.IntStream

/**
 * This renders a box and whisker plot for the PR lifetime data exported by ExportPrLifetimeData.
 *
 * Usage: invoke with <exported tsv> <destination svg>
 */
object RenderPrLifetimeBoxWhiskerPlot {

    @JvmStatic fun main(args: Array<String>) {

        if (args.size != 2) {
            System.err!!.println("Must have 2 arguments: <input tsv> <output svg>")
            System.exit(1)
        }

        val dataSource = getTsvBoxWhiskerDataSource(Paths.get(args[0]))
        val boxPlot = BoxPlot(dataSource)

        val xRenderer = boxPlot.getAxisRenderer(XYPlot.AXIS_X)
        xRenderer.label = "Week"

        xRenderer.customTicks = IntStream.rangeClosed(0, dataSource.columnCount)
                .mapToObj({ i -> i })
                .collect(Collectors.toMap({ i -> i.toDouble() }, { i -> i.toString() }))

        val yRenderer = boxPlot.getAxisRenderer(XYPlot.AXIS_Y)
        yRenderer.label = "Hours"
        yRenderer.labelDistance = 1.5

        val spread = dataSource.statistics.get(Statistics.MAX) - dataSource.statistics.get(Statistics.MIN)
        var chunk = 1
        while (spread / chunk > 20) {
            chunk *= 10
        }
        yRenderer.tickSpacing = chunk

        // Style the plot
        val insetsTop = 20.0
        val insetsLeft = 80.0
        val insetsBottom = 60.0
        val insetsRight = 0.0
        boxPlot.insets = Insets2D.Double(insetsTop, insetsLeft, insetsBottom, insetsRight);
        boxPlot.title.text = "PR Lifetime";

        Files.newOutputStream(Paths.get(args[1])).use { o ->
            val drawableWriter = DrawableWriterFactory.getInstance().get("image/svg+xml")
            drawableWriter.write(boxPlot, o, 1024.toDouble(), 1024.toDouble())
        }
    }


}

