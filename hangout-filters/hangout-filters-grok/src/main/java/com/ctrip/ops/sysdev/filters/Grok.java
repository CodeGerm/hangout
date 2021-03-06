package com.ctrip.ops.sysdev.filters;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.NameEntry;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;

import com.ctrip.ops.sysdev.baseplugin.BaseFilter;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class Grok extends BaseFilter {
	static private final Logger logger = LogManager.getLogger(Grok.class);
	private static final String PATTERN_DIR = "filtersLoader_pattern_dir";

    private String src;
    private String encoding;
    private List<Regex> matches;
    private Map<String, String> patterns;

    public Grok(Map config) {
        super(config);
    }

    private String convertPatternOneLevel(String p) {
        String pattern = "\\%\\{[_0-9a-zA-Z]+(:[-_.0-9a-zA-Z]+){0,2}\\}";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern)
                .matcher(p);
        String newPattern = "";
        int last_end = 0;
        while (m.find()) {
            newPattern += p.substring(last_end, m.start());
            String syntaxANDsemantic = m.group(0).substring(2,
                    m.group(0).length() - 1);
            String syntax, semantic;
            String[] syntaxANDsemanticArray = syntaxANDsemantic.split(":", 3);

            syntax = syntaxANDsemanticArray[0];

            if (syntaxANDsemanticArray.length > 1) {
                semantic = syntaxANDsemanticArray[1];
                newPattern += "(?<" + semantic + ">" + patterns.get(syntax)
                        + ")";
            } else {
                newPattern += patterns.get(syntax);
            }
            last_end = m.end();
        }
        newPattern += p.substring(last_end);
        return newPattern;
    }

    private String convertPattern(String p) {
        do {
            String rst = this.convertPatternOneLevel(p);
            if (rst.equals(p)) {
                return p;
            }
            p = rst;
        } while (true);
    }

    private void load_patterns(File path) {
        if (path.isDirectory()) {
            for (File subpath : path.listFiles())
                load_patterns(subpath);
        } else {
            try {
                BufferedReader br = new BufferedReader(new FileReader(path));
                String sCurrentLine;

                while ((sCurrentLine = br.readLine()) != null) {
                    sCurrentLine = sCurrentLine.trim();
                    if (sCurrentLine.length() == 0
                            || sCurrentLine.indexOf("#") == 0) {
                        continue;
                    }
                    this.patterns.put(sCurrentLine.split("\\s", 2)[0],
                            sCurrentLine.split("\\s", 2)[1]);
                }

                br.close();

            } catch (IOException e) {
                logger.error(e);
            }
        }

    }

    @SuppressWarnings("unchecked")
    protected void prepare() {
        this.patterns = new HashMap<>();

        final String path = "patterns";
        final File jarFile = new File(getClass().getProtectionDomain()
                .getCodeSource().getLocation().getPath().replaceAll("%20",  " "));

        //TODO NOT unzip to tmp
        if (jarFile.isFile()) { // Run with JAR file
            try {
                JarFile jar = new JarFile(jarFile);
                final Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    final String name = entries.nextElement().getName();
                    if (name.startsWith(path)) {
                        InputStream in = ClassLoader
                                .getSystemResourceAsStream(name);
                        File file = File.createTempFile(name, "");
                        try {
                            OutputStream os = new FileOutputStream(file);
                            int bytesRead = 0;
                            byte[] buffer = new byte[8192];
                            while ((bytesRead = in.read(buffer, 0, 8192)) != -1) {
                                os.write(buffer, 0, bytesRead);
                            }
                            os.close();
                            in.close();
                        } catch (Exception e) {
                            logger.warn(e);
                        }
                        try {
                            load_patterns(file);
                        } catch (Exception e) {
                            logger.warn(e);
                        }
                    }
                }
                jar.close();
            } catch (IOException e) {
                logger.error("failed to prepare patterns");
                logger.trace(e);
                throw new IllegalStateException("failed to prepare patterns", e);
            }

        } else { // Run with IDE
            try {
                load_patterns(new File(ClassLoader
                        .getSystemResource("patterns").getFile()));
            } catch (Exception e) {
                logger.warn(e);
            }
        }

        if (this.config.containsKey("pattern_paths")) {
            try {
                ArrayList<String> pattern_paths = (ArrayList<String>) this.config
                        .get("pattern_paths");

            	String dir = "";
            	if (System.getProperty(PATTERN_DIR) != null) {
            		dir = System.getProperty(PATTERN_DIR) + File.separator;
            		}
                for (String pattern_path : pattern_paths) {  
                    load_patterns(new File(dir + pattern_path));
                }
            } catch (Exception e) {
                logger.error("failed to read pattern_path");
                logger.trace(e);
                throw new IllegalStateException("failed to read pattern_path:", e);
            }
        }

        this.matches = new ArrayList<>();

        for (String matchString : (ArrayList<String>) this.config.get("match")) {
            matchString = convertPattern(matchString);
            Regex regex = null;
            try {
                regex = new Regex(matchString.getBytes("UTF8"), 0,
                        matchString.getBytes("UTF8").length, Option.NONE,
                        UTF8Encoding.INSTANCE);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                logger.error("failed to compile match pattern.");
                throw new IllegalStateException("failed to compile match pattern." + e.getMessage());
            }

            matches.add(regex);
        }

        if (this.config.containsKey("encoding")) {
            this.encoding = (String) this.config.get("encoding");
        } else {
            this.encoding = "UTF8";
        }

        if (this.config.containsKey("src")) {
            this.src = (String) this.config.get("src");
        } else {
            this.src = "message";
        }

        if (this.config.containsKey("tag_on_failure")) {
            this.tagOnFailure = (String) this.config.get("tag_on_failure");
        } else {
            this.tagOnFailure = "grokfail";
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Map filter(Map event) {
        if (!event.containsKey(this.src)) {
            return event;
        }
        boolean success = false;
        String input = ((String) event.get(this.src));
        byte[] bs = new byte[0];
        try {
            bs = input.getBytes(this.encoding);
        } catch (UnsupportedEncodingException e) {
            logger.error("input.getBytes error, maybe wrong encoding? try do NOT use encoding");
            bs = input.getBytes();
        }

        for (Regex regex : this.matches) {
            try {
                Matcher matcher = regex.matcher(bs);
                int result = matcher.search(0, bs.length, Option.DEFAULT);

                if (result != -1) {
                    success = true;

                    Region region = matcher.getEagerRegion();
                    for (Iterator<NameEntry> entry = regex
                            .namedBackrefIterator(); entry.hasNext(); ) {
                        NameEntry e = entry.next();
                        int[] backRefs = e.getBackRefs();
                        if (backRefs.length == 1) {
                            int number = backRefs[0];
                            int begin = region.beg[number];
                            int end = region.end[number];
                            if (begin != -1) {
                                event.put(new String(e.name, e.nameP, e.nameEnd
                                        - e.nameP), new String(bs, begin, end - begin, this.encoding));
                            }
                        } else {
                            ArrayList<String> value = new ArrayList<>();
                            for (int number : backRefs) {
                                int begin = region.beg[number];
                                int end = region.end[number];
                                if (begin != -1) {
                                    value.add(new String(bs, begin, end - begin, this.encoding));
                                }
                            }
                            event.put(new String(e.name, e.nameP, e.nameEnd
                                    - e.nameP), value);
                        }
                    }
                    break;
                }

            } catch (Exception e) {
                logger.warn("grok failed:" + event);
                logger.trace(e.getLocalizedMessage());
                success = false;
            }
        }

        this.postProcess(event, success);

        return event;
    }

}
