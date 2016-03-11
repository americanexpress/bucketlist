package io.aexp.bucketlist.examples

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.ning.http.client.AsyncHttpClient
import de.erichseifert.gral.data.DataSource
import de.erichseifert.gral.data.DataTable
import de.erichseifert.gral.io.data.DataReaderFactory
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.HttpBucketListClient
import io.aexp.bucketlist.auth.UsernamePasswordAuthenticator
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties


/**
 * Load username/password and url from a props file.
 *
 * This means that you never have to put the password in a shell command, so it can stay out of your shell history,
 * process listing, etc.
 */
fun getBitBucketClient(configPath: Path, asyncHttpClient: AsyncHttpClient = AsyncHttpClient()): BucketListClient {
    val props = Properties()
    props.load(Files.newBufferedReader(configPath, StandardCharsets.UTF_8))

    val username = props.getProperty("username")
    val password = props.getProperty("password")
    val url = URL(props.getProperty("url"))

    val objectMapper = ObjectMapper()
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    return HttpBucketListClient(url,
            UsernamePasswordAuthenticator(username, password),
            asyncHttpClient,
            objectMapper.reader(),
            objectMapper.writer())
}

/**
 * @return DataTable configured with the appropriate columns for a box and whisker plot
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
fun getBoxWhiskerPlotDataTable(): DataTable {
    // need boxed for DataTable, but Kotlin numeric types map to primitives
    val doubleClass = java.lang.Double::class.java
    return DataTable(Integer::class.java,
            doubleClass,
            doubleClass,
            doubleClass,
            doubleClass,
            doubleClass)
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
fun getTsvBoxWhiskerDataSource(outputPath: Path): DataSource {
    val dataReader = DataReaderFactory.getInstance().get("text/tab-separated-values")

    Files.newInputStream(outputPath).use { stream ->
        val doubleClass = java.lang.Double::class.java
        return dataReader.read(stream,
                Integer::class.java,
                doubleClass,
                doubleClass,
                doubleClass,
                doubleClass,
                doubleClass)
    }
}
