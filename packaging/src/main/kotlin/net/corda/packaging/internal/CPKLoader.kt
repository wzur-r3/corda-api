package net.corda.packaging.internal

import net.corda.packaging.CPK
import net.corda.packaging.CordappManifest
import net.corda.packaging.CordappManifestException
import net.corda.packaging.DependencyMetadataException
import net.corda.packaging.InvalidSignatureException
import net.corda.packaging.PackagingException
import net.corda.packaging.internal.PackagingConstants.CPK_DEPENDENCIES_FILE_ENTRY
import net.corda.packaging.internal.PackagingConstants.CPK_LIB_FOLDER
import net.corda.packaging.internal.PackagingConstants.JAR_FILE_EXTENSION
import net.corda.packaging.util.TeeInputStream
import net.corda.packaging.util.UncloseableInputStream
import net.corda.v5.base.util.loggerFor
import net.corda.v5.crypto.DigestAlgorithmName
import net.corda.v5.crypto.SecureHash
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXParseException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Base64
import java.util.Collections
import java.util.NavigableMap
import java.util.NavigableSet
import java.util.TreeMap
import java.util.TreeSet
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD
import javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA
import javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING
import javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.validation.SchemaFactory

internal object CPKLoader {
    private val logger = loggerFor<CPKLoader>()

    // The tags used in the XML of the file specifying a CPK's dependencies.
    private const val DEPENDENCY_TAG = "cpkDependency"
    private const val DEPENDENCY_NAME_TAG = "name"
    private const val DEPENDENCY_VERSION_TAG = "version"
    private const val DEPENDENCY_SIGNERS_TAG = "signers"
    private const val DEPENDENCY_TYPE_TAG = "type"
    private const val DEPENDENCY_SIGNER_TAG = "signer"
    private const val CORDA_CPK_V1 = "corda-cpk-1.0.xsd"

    private fun DocumentBuilderFactory.disableProperty(propertyName: String) {
        try {
            setAttribute(propertyName, "")
        } catch (_: IllegalArgumentException) {
            // Property not supported.
        }
    }

    private fun SchemaFactory.disableProperty(propertyName: String) {
        try {
            setProperty(propertyName, "")
        } catch (_: SAXNotRecognizedException) {
            // Property not supported.
        }
    }

    private val cpkV1DocumentBuilderFactory = DocumentBuilderFactory.newInstance().also { dbf ->
        dbf.setFeature(FEATURE_SECURE_PROCESSING, true)
        dbf.disableProperty(ACCESS_EXTERNAL_SCHEMA)
        dbf.disableProperty(ACCESS_EXTERNAL_DTD)
        dbf.isExpandEntityReferences = false
        dbf.isIgnoringComments = true
        dbf.isNamespaceAware = true

        val cpkSchema = SchemaFactory.newInstance(W3C_XML_SCHEMA_NS_URI).also { sf ->
            sf.setFeature(FEATURE_SECURE_PROCESSING, true)
            sf.disableProperty(ACCESS_EXTERNAL_SCHEMA)
            sf.disableProperty(ACCESS_EXTERNAL_DTD)
        }.newSchema(
            this::class.java.classLoader.getResource(CORDA_CPK_V1) ?: throw IllegalStateException("Corda CPK v1 schema missing")
        )
        dbf.schema = cpkSchema
    }

    @Suppress("LongParameterList")
    private class CPKContext(
        val buffer : ByteArray,
        val verifySignature : Boolean,
        var topLevelJars : Int,
        val fileLocationAppender: (String) -> String,
        var temporaryCPKFile : Path?,
        var cpkType : CPK.Type,
        val cpkDigest: MessageDigest,
        var cordappFileName: String?,
        val cordappDigest: MessageDigest,
        var cordappCertificates: Set<Certificate>?,
        var cordappManifest: CordappManifest?,
        var cpkManifest: CPK.Manifest?,
        val libraryMap: NavigableMap<String, SecureHash>,
        var cpkDependencies: NavigableSet<CPK.Identifier>
    ) {
        fun validate() {
            when {
                topLevelJars == 0 -> throw PackagingException(fileLocationAppender("No CorDapp JAR found"))
                topLevelJars > 1 -> throw PackagingException(fileLocationAppender("$topLevelJars CorDapp JARs found"))
            }
        }

        fun buildCPK(): CPK {
            val metadata = buildMetadata()
            val finalCPKFile = temporaryCPKFile?.let {
                val destination = it.parent.resolve(metadata.hash.toHexString())
                Files.move(it, destination)
                destination.toFile()
            } ?: throw IllegalStateException("This should never happen")

            return CPKImpl(
                metadata = metadata,
                jarFile = JarFile(finalCPKFile, verifySignature)
            )
        }

        fun buildMetadata(): CPK.Metadata {
            return CPKMetadataImpl(
                    type = cpkType,
                    manifest = cpkManifest!!,
                    mainBundle = cordappFileName!!,
                    hash = SecureHash(DigestAlgorithmName.SHA2_256.name, cpkDigest.digest()),
                    cordappManifest = cordappManifest!!,
                    cordappCertificates = cordappCertificates!!,
                    libraries = Collections.unmodifiableList(ArrayList(libraryMap.keys)),
                    dependencies = Collections.unmodifiableNavigableSet(cpkDependencies),
            )
        }
    }

    private fun isMainJar(jarEntry: ZipEntry): Boolean {
        return !jarEntry.isDirectory
                && jarEntry.name.let {
            it.indexOf('/') == -1 && it.endsWith(JAR_FILE_EXTENSION)
        }
    }

    private fun isLibJar(jarEntry: ZipEntry): Boolean {
        return !jarEntry.isDirectory
                && jarEntry.name.let {
            it.startsWith(CPK_LIB_FOLDER) && it.indexOf('/') == CPK_LIB_FOLDER.length &&
                    it.indexOf('/', CPK_LIB_FOLDER.length + 1) == -1 && it.endsWith(JAR_FILE_EXTENSION)
        }
    }

    private fun isManifest(zipEntry: ZipEntry) = zipEntry.name == JarFile.MANIFEST_NAME

    private fun consumeStream(inputStream: InputStream, buffer: ByteArray) {
        while (true) {
            if (inputStream.read(buffer) == -1) break
        }
    }

    @Suppress("NestedBlockDepth", "ThrowsCount", "ComplexMethod")
    private fun processMainJar(mainJarStream: InputStream, cpkEntry: ZipEntry, verifySignature: Boolean, ctx: CPKContext) {
        ctx.cordappFileName = cpkEntry.name
        val cordappDigestInputStream = DigestInputStream(mainJarStream, ctx.cordappDigest)
        val (manifest, certificates) = JarInputStream(cordappDigestInputStream, verifySignature).let { jar ->
            val signatureCollector = SignatureCollector()
            try {
                while (true) {
                    val jarEntry = jar.nextJarEntry ?: break
                    if (jarEntry.name == CPK_DEPENDENCIES_FILE_ENTRY) {
                        /** We need to do this as [readDependencies] closes the stream, while we still need it afterward **/
                        val uncloseableInputStream = UncloseableInputStream(jar)
                        ctx.cpkDependencies = readDependencies(cpkEntry.name, uncloseableInputStream, ctx.fileLocationAppender)
                    } else {
                        consumeStream(jar, ctx.buffer)
                    }
                    try {
                        signatureCollector.addEntry(jarEntry)
                    } catch (e: IOException) {
                        throw PackagingException(ctx.fileLocationAppender("Could not retrieve certificates of CorDapp JAR"), e)
                    }
                    jar.closeEntry()
                }
            } catch (se: SecurityException) {
                throw InvalidSignatureException(ctx.fileLocationAppender("Detected invalid signature on '${ctx.cordappFileName}'"), se)
            }
            val manifest = jar.manifest ?: throw CordappManifestException(
                    ctx.fileLocationAppender("Missing manifest from cordapp jar '${ctx.cordappFileName}'"))
            manifest.mainAttributes.getValue(CPKManifestImpl.CPK_TYPE)?.let(CPK.Type::parse)?.also { ctx.cpkType = it }
            CordappManifest.fromManifest(manifest) to signatureCollector.certificates
        }
        consumeStream(cordappDigestInputStream, ctx.buffer)
        ctx.cordappManifest = manifest
        ctx.cordappCertificates = certificates
        ctx.topLevelJars++
    }

    private fun processLibJar(inputStream: InputStream, cpkEntry: ZipEntry, ctx: CPKContext) {
        val digestBytes = try {
            val libraryMessageDigest = MessageDigest.getInstance(DigestAlgorithmName.SHA2_256.name)
            consumeStream(DigestInputStream(inputStream, libraryMessageDigest), ctx.buffer)
            libraryMessageDigest.digest()
        } catch (e: IOException) {
            throw PackagingException(ctx.fileLocationAppender("Could not calculate hash of library jar '${cpkEntry.name}"), e)
        }
        ctx.libraryMap[cpkEntry.name] = SecureHash(DigestAlgorithmName.SHA2_256.name, digestBytes)
    }

    /**
     * Parses the cpk metadata optionally extracting the cpk content in the provided destination
     * [cacheDir] if it is present. A [cpkLocation] string can be provided for more meaningful
     * exception messages in case of errors
     */
    @Suppress("ComplexMethod")
    private fun createContext(source: InputStream, cacheDir: Path?, cpkLocation: String?, verifySignature: Boolean): CPKContext {
        val ctx = CPKContext(
                buffer = ByteArray(DEFAULT_BUFFER_SIZE),
                verifySignature = verifySignature,
                topLevelJars = 0,
                temporaryCPKFile = cacheDir
                    ?.let((Files::createDirectories))
                    ?.let { Files.createTempFile(it, null, ".cpk") },
                cpkType = CPK.Type.UNKNOWN,
                cpkDigest = MessageDigest.getInstance(DigestAlgorithmName.SHA2_256.name),
                cordappFileName = null,
                cordappDigest = MessageDigest.getInstance(DigestAlgorithmName.SHA2_256.name),
                cordappCertificates = null,
                cordappManifest = null,
                cpkManifest = null,
                libraryMap = TreeMap(),
                cpkDependencies = TreeSet(),
                fileLocationAppender = if (cpkLocation == null) {
                    { msg: String -> msg }
                } else {
                    { msg: String -> "$msg in CPK at $cpkLocation" }
                }
        )
        var stream2BeFullyConsumed : InputStream = DigestInputStream(source, ctx.cpkDigest)
        val temporaryCPKFile = ctx.temporaryCPKFile
        if (temporaryCPKFile == null) {
            JarInputStream(stream2BeFullyConsumed, verifySignature)
        } else {
            Files.createDirectories(temporaryCPKFile.parent)
            stream2BeFullyConsumed = TeeInputStream(stream2BeFullyConsumed, Files.newOutputStream(temporaryCPKFile))
            JarInputStream(stream2BeFullyConsumed, verifySignature)
        }.use { jarInputStream ->
            val jarManifest = jarInputStream.manifest ?: throw PackagingException(ctx.fileLocationAppender("Invalid file format"))
            ctx.cpkManifest = CPK.Manifest.fromJarManifest(Manifest(jarManifest))
            while (true) {
                val cpkEntry = jarInputStream.nextEntry ?: break
                when {
                    isMainJar(cpkEntry) -> {
                        processMainJar(jarInputStream, cpkEntry, verifySignature, ctx)
                    }
                    isLibJar(cpkEntry) -> processLibJar(jarInputStream, cpkEntry, ctx)
                    isManifest(cpkEntry) -> {
                        ctx.cpkManifest = CPK.Manifest.fromJarManifest(Manifest(jarInputStream))
                    }
                }
                jarInputStream.closeEntry()
            }
            consumeStream(stream2BeFullyConsumed, ctx.buffer)
        }
        ctx.validate()
        return ctx
    }

    fun loadCPK(source: InputStream, cacheDir: Path?, cpkLocation: String?, verifySignature: Boolean) =
        createContext(source, cacheDir, cpkLocation, verifySignature).buildCPK()

    fun loadMetadata(source: InputStream, cpkLocation: String?, verifySignature: Boolean) =
        createContext(source, null, cpkLocation, verifySignature).buildMetadata()

    /** Returns the [CPK.Identifier]s for the provided [dependenciesFileContent]. */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught", "ComplexMethod")
    private fun readDependencies(jarName: String,
                                 dependenciesFileContent: InputStream,
                                 fileLocationAppender : (String) -> String): NavigableSet<CPK.Identifier> {
        val cpkDependencyDocument: Document = try {
            // The CPK dependencies are specified as an XML file.
            val documentBuilder = cpkV1DocumentBuilderFactory.newDocumentBuilder()
            documentBuilder.setErrorHandler(XmlErrorHandler(jarName))
            documentBuilder.parse(dependenciesFileContent)
        } catch (e: DependencyMetadataException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw DependencyMetadataException(fileLocationAppender("Error parsing CPK dependencies file"), e)
        }
        val cpkDependencyNodes = cpkDependencyDocument.getElementsByTagName(DEPENDENCY_TAG)

        /**
         * [Iterator] for traversing every [Element] within a [NodeList].
         * Skips any [org.w3c.dom.Node] that is not also an [Element].
         */
        class ElementIterator(private val nodes: NodeList) : Iterator<Element> {
            private var index = 0
            override fun hasNext() = index < nodes.length
            override fun next() = if (hasNext()) nodes.item(index++) as Element else throw NoSuchElementException()
        }
        return ElementIterator(cpkDependencyNodes).asSequence().withIndex().mapNotNull { el ->
            val dependencyNameElements = el.value.getElementsByTagName(DEPENDENCY_NAME_TAG)
            val dependencyVersionElements = el.value.getElementsByTagName(DEPENDENCY_VERSION_TAG)
            val dependencyTypeElements = el.value.getElementsByTagName(DEPENDENCY_TYPE_TAG)
            val dependencySignersElements = el.value.getElementsByTagName(DEPENDENCY_SIGNERS_TAG)

            if (dependencyNameElements.length != 1) {
                // Should already have been validated by schema.
                val msg = fileLocationAppender("The entry at index ${el.index} of the CPK dependencies did not specify its name correctly.")
                throw DependencyMetadataException(msg)
            }
            if (dependencyVersionElements.length != 1) {
                // Should already have been validated by schema.
                val msg = fileLocationAppender("The entry at index ${el.index} of the CPK dependencies file did not specify its version correctly.")
                throw DependencyMetadataException(msg)
            }
            if (dependencySignersElements.length != 1) {
                // Should already have been validated by schema.
                val msg = fileLocationAppender("The entry at index ${el.index} of the CPK dependencies file did not specify its signers correctly.")
                throw DependencyMetadataException(msg)
            }

            val signers = dependencySignersElements.item(0) as Element

            val publicKeySummaryHash: SecureHash? =
                ElementIterator(signers.getElementsByTagName(DEPENDENCY_SIGNER_TAG)).asSequence().map { signer ->
                    val algorithm = signer.getAttribute("algorithm")?.trim()
                    if (algorithm.isNullOrEmpty()) {
                        // Should already have been validated by schema.
                        val msg = fileLocationAppender("The entry at index ${el.index} of the CPK dependencies file" +
                                " did not specify an algorithm for its signer's public key hash.")
                        throw DependencyMetadataException(msg)
                    }
                    val hashData = Base64.getDecoder().decode(signer.textContent)
                    SecureHash(algorithm, hashData)
                }.summaryHash()
            CPKIdentifierImpl(
                    dependencyNameElements.item(0).textContent,
                    dependencyVersionElements.item(0).textContent,
                    publicKeySummaryHash).takeIf {
                when(dependencyTypeElements.length) {
                    0 -> true
                    1 -> CPK.Type.parse(dependencyTypeElements.item(0).textContent) != CPK.Type.CORDA_API
                    else -> {
                        val msg = fileLocationAppender("The entry at index ${el.index} of the CPK dependencies file has multiple types")
                        throw DependencyMetadataException(msg)
                    }
                }
            }
        }.toCollection(TreeSet())
    }

    private class XmlErrorHandler(private val jarName: String) : ErrorHandler {
        override fun warning(ex: SAXParseException) {
            logger.warn("Problem at (line {}, column {}) parsing CPK dependencies for {}: {}",
                    ex.lineNumber, ex.columnNumber, jarName, ex.message)
        }

        override fun error(ex: SAXParseException) {
            logger.error("Error at (line {}, column {}) parsing CPK dependencies for {}: {}",
                    ex.lineNumber, ex.columnNumber, jarName, ex.message)
            throw DependencyMetadataException(ex.message ?: "", ex)
        }

        override fun fatalError(ex: SAXParseException) {
            logger.error("Fatal error at (line {}, column {}) parsing CPK dependencies for {}: {}",
                    ex.lineNumber, ex.columnNumber, jarName, ex.message)
            throw ex
        }
    }
}