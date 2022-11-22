package app.revanced.cli.command

import app.revanced.cli.logging.impl.DefaultCliLogger
import app.revanced.cli.patcher.logging.impl.PatcherLogger
import app.revanced.cli.signing.SigningOptions
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.apk.Apk
import app.revanced.patcher.apk.ApkBundle
import app.revanced.patcher.extensions.PatchExtensions.compatiblePackages
import app.revanced.patcher.extensions.PatchExtensions.description
import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.patch.PatchResult
import app.revanced.patcher.util.patch.PatchBundle
import app.revanced.utils.OptionsLoader
import app.revanced.utils.adb.Adb
import app.revanced.utils.filesystem.ZipFileUtils
import app.revanced.utils.patcher.addPatchesFiltered
import app.revanced.utils.signing.Signer
import app.revanced.utils.signing.align.ZipAligner
import picocli.CommandLine.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private class CLIVersionProvider : IVersionProvider {
    override fun getVersion() = arrayOf(
        MainCommand::class.java.`package`.implementationVersion ?: "unknown"
    )
}

@Command(
    name = "ReVanced-CLI",
    mixinStandardHelpOptions = true,
    versionProvider = CLIVersionProvider::class
)
internal object MainCommand : Runnable {
    val logger = DefaultCliLogger()

    @ArgGroup(exclusive = false, multiplicity = "1")
    lateinit var args: Args

    /**
     * Arguments for the CLI
     */
    class Args {
        @Option(names = ["--uninstall"], description = ["Uninstall the mounted apk by its package name"])
        var uninstall: String? = null

        @Option(names = ["-d", "--deploy-on"], description = ["If specified, deploy to adb device with given name"])
        var deploy: String? = null

        @Option(names = ["--mount"], description = ["If specified, instead of installing, mount"])
        var mount: Boolean = false

        @ArgGroup(exclusive = false)
        var patchArgs: PatchArgs? = null

        /**
         * Arguments for patches.
         */
        class PatchArgs {
            @Option(names = ["-b", "--bundle"], description = ["One or more bundles of patches"], required = true)
            var patchBundles = arrayOf<String>()

            @ArgGroup(exclusive = false)
            var listingArgs: ListingArgs? = null

            @ArgGroup(exclusive = false)
            var patchingArgs: PatchingArgs? = null

            /**
             * Arguments for patching.
             */
            class PatchingArgs {
                @ArgGroup(exclusive = false, multiplicity = "1")
                val apkArgs: ApkArgs? = null

                @Option(names = ["-o", "--out"], description = ["Output folder path"], required = true)
                var outputPath: File = File("revanced")

                @Option(names = ["--options"], description = ["Configuration file for all patch options"])
                var options: File =  outputPath.resolve("options.toml")

                @Option(names = ["-e", "--exclude"], description = ["Explicitly exclude patches"])
                var excludedPatches = arrayOf<String>()

                @Option(
                    names = ["--exclusive"],
                    description = ["Only installs the patches you include, excluding patches by default"]
                )
                var defaultExclude = false

                @Option(names = ["-i", "--include"], description = ["Include patches"])
                var includedPatches = arrayOf<String>()

                @Option(names = ["--experimental"], description = ["Disable patch version compatibility patch"])
                var experimental: Boolean = false

                @Option(names = ["-m", "--merge"], description = ["One or more dex file containers to merge"])
                var mergeFiles = listOf<File>()

                @Option(names = ["--cn"], description = ["Overwrite the default CN for the signed file"])
                var cn = "ReVanced"

                @Option(names = ["--keystore"], description = ["File path to your keystore"])
                var keystorePath: String? = null

                @Option(
                    names = ["-p", "--password"],
                    description = ["Overwrite the default password for the signed file"]
                )
                var password = "ReVanced"

                @Option(names = ["-t", "--temp-dir"], description = ["Temporary work directory"])
                var workDirectory = File("revanced-cache")

                @Option(
                    names = ["-c", "--clean"],
                    description = ["Clean the temporary work directory. This will always be done before running the patcher"]
                )
                var clean: Boolean = false

                @Option(names = ["--custom-aapt2-binary"], description = ["Path to custom aapt2 binary"])
                var aaptPath: String = ""

                @Option(
                    names = ["--low-storage"],
                    description = ["Minimizes storage usage by trying to cache as little as possible"]
                )
                var lowStorage: Boolean = false

                /**
                 * Arguments for [Apk] files.
                 */
                class ApkArgs {
                    @Option(
                        names = ["-a", "--base-apk"],
                        description = ["The base apk file that is to be patched"],
                        required = true
                    )
                    lateinit var baseApk: String

                    @ArgGroup(exclusive = false)
                    val splitsArgs: SplitsArgs? = null

                    class SplitsArgs {
                        @Option(
                            names = ["--language-apk"],
                            description = ["Additional split apk file which contains language files"], required = true
                        )
                        lateinit var languageApk: String

                        @Option(
                            names = ["--library-apk"],
                            description = ["Additional split apk file which contains libraries"], required = true
                        )
                        lateinit var libraryApk: String

                        @Option(
                            names = ["--asset-apk"],
                            description = ["Additional split apk file which contains assets"], required = true
                        )
                        lateinit var assetApk: String
                    }
                }
            }

            /**
             * Arguments for printing patches.
             */
            class ListingArgs {
                @Option(names = ["-l", "--list"], description = ["List patches only"], required = true)
                var listOnly: Boolean = false

                @Option(names = ["--with-versions"], description = ["List patches with compatible versions"])
                var withVersions: Boolean = false

                @Option(names = ["--with-packages"], description = ["List patches with compatible packages"])
                var withPackages: Boolean = false

                @Option(names = ["--with-descriptions"], description = ["List patches with their descriptions"])
                var withDescriptions: Boolean = true
            }
        }
    }

    override fun run() {
        // other types of commands
        // TODO: convert this code to picocli subcommands
        if (args.patchArgs?.listingArgs?.listOnly == true) return printListOfPatches()
        if (args.uninstall != null) return uninstall()

        // patching commands require these arguments
        val patchArgs = this.args.patchArgs ?: return
        val patchingArgs = patchArgs.patchingArgs ?: return

        // prepare the work directory, delete it if it already exists
        val workDirectory = patchingArgs.workDirectory.also {
            if (!it.deleteRecursively())
                return logger.error("Failed to delete work directory")
        }

        // prepare apks
        val apkArgs = patchingArgs.apkArgs!!

        val baseApk = Apk.Base(apkArgs.baseApk)
        val splitApk = apkArgs.splitsArgs?.let { args ->
            with(args) {
                ApkBundle.Split(
                    Apk.Split.Library(libraryApk),
                    Apk.Split.Asset(assetApk),
                    Apk.Split.Language(languageApk)
                )
            }
        }

        // prepare patches
        val allPatches = patchArgs.patchBundles.flatMap { bundle -> PatchBundle.Jar(bundle).loadPatches() }.also {
            OptionsLoader.init(patchingArgs.options, it)
        }

        // prepare the patcher
        val patcher = Patcher( // constructor decodes base
            PatcherOptions(
                ApkBundle(baseApk, splitApk),
                workDirectory.path,
                patchingArgs.aaptPath,
                workDirectory.path,
                PatcherLogger
            )
        )

        // prepare adb
        val adb: Adb? = args.deploy?.let { device ->
            if (args.mount) {
                Adb.RootAdb(device, logger)
            } else {
                Adb.UserAdb(device, logger)
            }
        }

        with(workDirectory.resolve("cli")) {
            val patched = resolve("patched")
            val alignedDirectory = resolve("aligned").also(File::mkdirs)
            val signedDirectory = resolve("signed").also(File::mkdirs)


            /**
             * Clean up a temporal directory.
             *
             * @param directory The directory to clean up.
             */
            fun delete(directory: File, force: Boolean = false) {
                if (!force && !patchingArgs.lowStorage) return
                if (!directory.deleteRecursively())
                    return logger.error("Failed to delete directory $directory")
            }

            /**
             * Creates the [Apk] file with the patches resources.
             *
             * @param apk The [Apk] file to write.
             * @return The new patched [Apk] file.
             */
            fun writeToNewApk(apk: Apk): File {
                /**
                 * Writes the [Apk] patch to the file specified.
                 *
                 * @param file The file to copy to.
                 */
                fun writeToFile(file: File) {
                    ZipFileUtils(file).use { apkFileSystem ->
                        // copy resources for that apk to the cached apk
                        apk.resources?.let { apkResources ->
                            logger.info("Creating new resources to new $apk apk file")
                            ZipFileUtils(apkResources).use { resourcesFileStream ->
                                // get the resources from the resources file and write them to the cached apk
                                val resourceFiles = resourcesFileStream.getFsPath(File.separator)
                                apkFileSystem.write(resourceFiles)
                            }

                            // store resources which are doNotCompress
                            // TODO(perf): make FileSystemUtils compress by default
                            //  by using app.revanced.utils.signing.align.zip.ZipFile
                            apk.packageMetadata.doNotCompress?.forEach(apkFileSystem::decompress)
                        }

                        // copy dex files for that apk to the cached apk, if it is a base apk
                        if (apk is Apk.Base) {
                            logger.info("Writing dex files for $apk apk file")
                            apk.dexFiles.forEach { dexFile ->
                                apkFileSystem.write(dexFile.name, dexFile.stream.readAllBytes())
                            }
                        }
                    }
                }

                return patched.resolve(apk.file.name) // no need to mkdirs, because copyTo will create the path
                    .also { apk.file.copyTo(it) } // write a copy of the original file
                    .also(::writeToFile) // write patches to that file
            }

            /**
             * Alin the raw [Apk] file.
             *
             * @param unalignedApkFile The apk file to align.
             * @return The aligned [Apk] file.
             */
            fun alignApk(unalignedApkFile: File): File {
                logger.info("Aligning ${unalignedApkFile.name}")
                return alignedDirectory.resolve(unalignedApkFile.name)
                    .also { alignedApk -> ZipAligner.align(unalignedApkFile, alignedApk) }
            }

            /**
             * Sign a list of [Apk] files.
             *
             * @param unsignedApks The list of [Apk] files to sign.
             * @return The list of signed [Apk] files.
             */
            fun signApks(unsignedApks: List<File>) = if (!args.mount) {
                with(Signer(
                    SigningOptions(
                        patchingArgs.cn,
                        patchingArgs.password,
                        patchingArgs.keystorePath
                            ?: patchingArgs.outputPath.absoluteFile.resolve("${baseApk.file.nameWithoutExtension}.keystore").canonicalPath
                    )
                )){
                    unsignedApks.map { unsignedApk -> // sign the unsigned apk
                        logger.info("Signing ${unsignedApk.name}")
                        signedDirectory.resolve(unsignedApk.name)
                            .also { signedApk ->
                                signApk(
                                    unsignedApk, signedApk
                                )
                            }
                    }
                }
            } else {
                unsignedApks
            }

            /**
             * Copy an [Apk] file to the output directory.
             *
             * @param apk The [Apk] file to copy.
             * @return The copied [Apk] file.
             */
            fun copyToOutput(apk: File): File {
                logger.info("Copying ${apk.name} to output directory")

                return patchingArgs.outputPath.resolve(apk.name).also {
                    Files.copy(apk.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }

            /**
             * Install an [Apk] file to the device.
             *
             * @param apkFiles The [Apk] files to install.
             * @return The input [Apk] file.
             */
            fun install(apkFiles: List<Pair<File, Apk>> /* pair of the apk file and the apk */) =
                apkFiles.also {
                    adb?.let { adb ->
                        fun Pair<File, Apk>.intoAdbApk() = Adb.Apk(this.first.path)

                        val base = it.find { (_, apk) -> apk is Apk.Base }!!.let(Pair<File, Apk>::intoAdbApk)
                        val splits = it.filter { (_, apk) -> apk is Apk.Split }.map(Pair<File, Apk>::intoAdbApk)

                        adb.install(base, splits)
                    }
                }.map { (outputApk, _) -> outputApk }

            /**
             * Clean up the work directory and output files.
             *
             * @param outputApks The list of output [Apk] files.
             */
            fun cleanUp(outputApks: List<File>) {
                // clean up the work directory if needed
                if (patchingArgs.clean) {
                    delete(patchingArgs.workDirectory, true)
                    if (args.deploy?.let { outputApks.any { !it.delete() } } == true)
                        logger.error("Failed to delete some output files")
                }
            }

            /**
             * Run the patcher and save the patched resources
             *
             * @return The resulting patched [Apk] files.
             */
            fun Patcher.run() = also {
                addFiles(patchingArgs.mergeFiles) { file ->
                    logger.info("Merging $file")
                }

                addPatchesFiltered(allPatches, baseApk)

                this.executePatches().forEach { (patch, result) ->
                    if (result is PatchResult.Error) logger.error("$patch failed:\n${result.stackTraceToString()}")
                    else logger.info("$patch succeeded")
                }
            }.save().apkFiles.map { it.apk }

            with(patcher.run()) {
                map(::writeToNewApk)
                    .map(::alignApk).also { delete(patched) }
                    .also { patchingArgs.outputPath.also(File::mkdirs) } // from now on this directory is required
                    .let(::signApks).also { delete(alignedDirectory) }
                    .map(::copyToOutput).also { delete(signedDirectory) }.zip(this)
                    .let(::install)
                    .let(::cleanUp)
            }

            logger.info("Finished")
        }
    }

    private fun uninstall() {
        args.uninstall?.let { packageName ->
            args.deploy?.let { device ->
                Adb.UserAdb(device, logger).uninstall(packageName)
            } ?: return logger.error("You must specify a device to uninstall from")
        }
    }

    private fun printListOfPatches() {
        val logged = mutableListOf<String>()
        for (patchBundlePath in args.patchArgs?.patchBundles!!) for (patch in PatchBundle.Jar(patchBundlePath)
            .loadPatches()) {
            if (patch.patchName in logged) continue
            for (compatiblePackage in patch.compatiblePackages!!) {
                val packageEntryStr = buildString {
                    // Add package if flag is set
                    if (args.patchArgs?.listingArgs?.withPackages == true) {
                        val packageName = compatiblePackage.name.substringAfterLast(".").padStart(10)
                        append(packageName)
                        append("\t")
                    }
                    // Add patch name
                    val patchName = patch.patchName.padStart(25)
                    append(patchName)
                    // Add description if flag is set.
                    if (args.patchArgs?.listingArgs?.withDescriptions == true) {
                        append("\t")
                        append(patch.description)
                    }
                    // Add compatible versions, if flag is set
                    if (args.patchArgs?.listingArgs?.withVersions == true) {
                        val compatibleVersions = compatiblePackage.versions.joinToString(separator = ", ")
                        append("\t")
                        append(compatibleVersions)
                    }
                }

                logged.add(patch.patchName)
                logger.info(packageEntryStr)
            }
        }
    }
}
