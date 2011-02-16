package press;

import java.io.File;
import java.lang.reflect.Method;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

import play.Play;
import play.PlayPlugin;

public class Plugin extends PlayPlugin {
    static ThreadLocal<JSCompressor> jsCompressor = new ThreadLocal<JSCompressor>();
    static ThreadLocal<CSSCompressor> cssCompressor = new ThreadLocal<CSSCompressor>();
    static ThreadLocal<Boolean> errorOccurred = new ThreadLocal<Boolean>();
    static ThreadLocal<Map<String, Boolean>> jsFiles = new ThreadLocal<Map<String, Boolean>>();
    static ThreadLocal<Map<String, Boolean>> cssFiles = new ThreadLocal<Map<String, Boolean>>();

    @Override
    public void onApplicationStart() {
        // Read the config each time the application is restarted
        PluginConfig.readConfig();

        // Clear the cache
        JSCompressor.clearCache();
        CSSCompressor.clearCache();
    }

    @Override
    public void beforeActionInvocation(Method actionMethod) {
        // Before each action, reinitialize variables
        jsCompressor.set(new JSCompressor());
        cssCompressor.set(new CSSCompressor());
        errorOccurred.set(false);
        jsFiles.set(new HashMap<String, Boolean>());
        cssFiles.set(new HashMap<String, Boolean>());
    }

    /**
     * Get the url for the compressed version of the given JS file, in real time
     */
    public static String compressedSingleJSUrl(String fileName) {
        return jsCompressor.get().compressedSingleFileUrl(fileName);
    }

    /**
     * Get the url for the compressed version of the given CSS file, in real
     * time
     */
    public static String compressedSingleCSSUrl(String fileName) {
        return cssCompressor.get().compressedSingleFileUrl(fileName);
    }

    /**
     * Check if the given JS file exists.
     */
    public static void checkJSFileExists(String fileName) {
        JSCompressor.checkJSFileExists(fileName);
    }

    /**
     * Check if the given CSS file exists.
     */
    public static void checkCSSFileExists(String fileName) {
        CSSCompressor.checkCSSFileExists(fileName);
    }

    /**
     * Check if the given JS file has already been included.
     */
    public static void checkForJSDuplicates(String fileName, boolean compress) {
        checkJSFileExists(fileName);
        checkForDuplicates(jsFiles.get(), fileName, JSCompressor.FILE_TYPE, JSCompressor.TAG_NAME);
    }

    /**
     * Check if the given CSS file has already been included.
     */
    public static void checkForCSSDuplicates(String fileName, boolean compress) {
        checkCSSFileExists(fileName);
        checkForDuplicates(cssFiles.get(), fileName, CSSCompressor.FILE_TYPE,
                CSSCompressor.TAG_NAME);
    }

    private static void checkForDuplicates(Map<String, Boolean> files, String fileName,
            String fileType, String tagName) {

        if (!files.containsKey(fileName)) {
            files.put(fileName, true);
            return;
        }

        throw new DuplicateFileException(fileType, fileName, tagName);
    }

    /**
     * Resolves a potentially globbed filename to a list of filenames:
     *
     * @example getResolvedFiles("my-app/*.js"); // => { "my-app/a.js", "my-app/b.js" }
     *
     * Non-globbed paths are returned as-is:
     *
     * @example getResolvedFiles("my-app/foo.css"); // => { "my-app/foo.css" }
     *
     * Note that patterns with partial filenames ("foo*.js") aren't supported.
     * Filenames ending in "**.js" are treated recursively.
     *
     * The fileName is expected to be in the same form as with addJS() (that is,
     * excluding the press.js.sourceDir part etc).  The same goes for the
     * returned paths.
     *
     * @param fileName  filename as given in template
     * @param sourceDir filename prefix as given in configuration
     * @return
     */
    @SuppressWarnings("unchecked")
    private static List<String> getResolvedFiles(String fileName, String sourceDir) {

        List<String> sources = new ArrayList<String>();

        String regex = "(?:.*/)?(\\*\\*?)\\.(\\w+)";
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(fileName);

        if (!m.matches()) {
            sources.add(fileName);
            return sources;
        }

        String extension = m.group(2);
        boolean isRecursive = m.group(1).length() == 2;

        fileName = fileName.substring(0, fileName.length() - extension.length() - (isRecursive ? 3 : 2));

        String fullPath = Play.applicationPath.getAbsolutePath() + sourceDir;
        String[] extensionFilter = { extension };
        File startLookingFrom = new File(fullPath + fileName);
        Collection<File> files = FileUtils.listFiles(startLookingFrom, extensionFilter, isRecursive);

        for (File file : files) {
            String relativePath = file.getAbsolutePath().substring(fullPath.length());
            sources.add(relativePath);
        }

        Collections.sort(sources, Collator.getInstance(Locale.US)); // sort by US ASCII by default

        return sources;
    }

    /**
     * Returns file signature(s) for given JavaScript file(s) that should be
     * included in the HTML without any changes.
     */
    public static String addUntouchedJS(String fileName) {
        String baseURL = jsCompressor.get().srcDir;
        String result = "";

        for (String src : getResolvedFiles(fileName, baseURL)) {
            press.Plugin.checkForJSDuplicates(src, true);
            result += getScriptTag(baseURL + src);
        }

        return result;
    }

    /**
     * Returns file signature(s) for given CSS file(s) that should be included
     * in the HTML without any changes.
     */
    public static String addUntouchedCSS(String fileName) {
        String baseURL = cssCompressor.get().srcDir;
        String result = "";

        for (String src : getResolvedFiles(fileName, baseURL)) {
            press.Plugin.checkForCSSDuplicates(src, true);
            result += getLinkTag(baseURL + src);
        }

        return result;
    }

    /**
     * Returns a script tag which can be used to output untouched JavaScript
     * tags within the HTML.
     */
    private static String getScriptTag(String src) {
        return "<script src=\"" + src + "\" type=\"text/javascript\" language=\"javascript\" charset=\"utf-8\"></script>\n";
    }

    /**
     * Returns a link tag which can be used to output untouched CSS tags within
     * the HTML.
     */
    private static String getLinkTag(String src) {
        return "<link href=\"" + src + "\" rel=\"stylesheet\" type=\"text/css\" charset=\"utf-8\">" + (press.PluginConfig.htmlCompatible ? "" : "</link>") + "\n";
    }

    /**
     * Adds the given file to the JS compressor, returning the file signature to
     * be output in HTML
     */
    public static String addJS(String fileName, boolean compress) {
        JSCompressor compressor = jsCompressor.get();
        String result = "";

        for (String src : getResolvedFiles(fileName, compressor.srcDir))
            result += compressor.add(src, compress);

        return result;
    }

    /**
     * Adds the given file to the CSS compressor, returning the file signature
     * to be output in HTML
     */
    public static String addCSS(String fileName, boolean compress) {
        CSSCompressor compressor = cssCompressor.get();
        String result = "";

        for (String src : getResolvedFiles(fileName, compressor.srcDir))
            result += compressor.add(src, compress);

        return result;
    }

    /**
     * Called when the template outputs the tag indicating where the compressed
     * javascript should be included. This method returns the URL of the
     * compressed file.
     */
    public static String compressedJSUrl() {
        return jsCompressor.get().compressedUrl();
    }

    /**
     * Called when the template outputs the tag indicating where the compressed
     * CSS should be included. This method returns the URL of the compressed
     * file.
     */
    public static String compressedCSSUrl() {
        return cssCompressor.get().compressedUrl();
    }

    @Override
    public void afterActionInvocation() {
        // At the end of the action, save the list of files that will be
        // associated with this request
        if (jsCompressor.get() != null && cssCompressor.get() != null) {
            jsCompressor.get().saveFileList();
            cssCompressor.get().saveFileList();
        }
    }

    @Override
    public void onInvocationException(Throwable e) {
        errorOccurred.set(true);
    }

    /**
     * Indicates whether or not an error has occurred
     */
    public static boolean hasErrorOccurred() {
        return errorOccurred.get() == null || errorOccurred.get();
    }

    /**
     * Indicates whether or not compression is enabled.
     */
    public static boolean enabled() {
        return PluginConfig.enabled;
    }

    /**
     * Indicates whether or not to compress files
     */
    public static boolean performCompression() {
        return enabled() && !hasErrorOccurred();
    }
}
