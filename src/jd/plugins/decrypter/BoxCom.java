//    jDownloader - Downloadmanager
//    Copyright (C) 2012  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.decrypter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.HTMLParser;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.components.PluginJSonUtils;

import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.plugins.components.antiDDoSForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "box.com" }, urls = { "https?://(?:www|[a-z0-9\\-_]*)(?:\\.?app)?\\.box\\.(?:net|com)/(?:shared|s)/(?!static)[a-z0-9]+(/\\d+/\\d+)?" })
public class BoxCom extends antiDDoSForDecrypt {
    private static final Pattern FEED_FILEINFO_PATTERN        = Pattern.compile("<item>(.*?)<\\/item>", Pattern.DOTALL);
    private static final Pattern FEED_FILETITLE_PATTERN       = Pattern.compile("<title>(.*?)<\\/title>", Pattern.DOTALL);
    private static final Pattern FEED_DL_LINK_PATTERN         = Pattern.compile("<media:content url=\\\"(.*?)\\\"\\s*/>", Pattern.DOTALL);
    private static final Pattern SINGLE_DOWNLOAD_LINK_PATTERN = Pattern.compile("(https?://(www|[a-z0-9\\-_]+)\\.box\\.com/index\\.php\\?rm=box_download_shared_file\\&amp;file_id=.+?\\&amp;shared_name=\\w+)");
    private static final String  ERROR                        = "(<h2>The webpage you have requested was not found\\.</h2>|<h1>404 File Not Found</h1>|Oops &mdash; this shared file or folder link has been removed\\.|RSS channel not found)";

    private static final String  TYPE_APP                     = "https?://(?:\\w+\\.)?app\\.box\\.com/(s|shared)/[a-z0-9]+(/1/\\d+)?";

    public BoxCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public ArrayList<DownloadLink> decryptIt(final CryptedLink parameter, final ProgressController progress) throws Exception {
        final ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        String cryptedlink = parameter.toString().replace("box.net/", "box.com/").replace("box.com/s/", "box.com/shared/");
        logger.finer("Decrypting: " + cryptedlink);
        br.setCookie("http://box.com", "country_code", "US");
        br.setFollowRedirects(true);
        br.getPage(cryptedlink);
        if (br.getURL().equals("https://www.box.com/freeshare")) {
            decryptedLinks.add(createOfflinelink(cryptedlink));
            return decryptedLinks;
        }
        if (br.getHttpConnection().getResponseCode() == 404 || br.containsHTML("<title>Box \\| 404 Page Not Found</title>") || br.containsHTML("error_message_not_found")) {
            decryptedLinks.add(createOfflinelink(cryptedlink));
            return decryptedLinks;
        }
        String fpName = null;
        if (br.getURL().matches(TYPE_APP)) {
            final String cp = PluginJSonUtils.getJsonValue(br, "current_page");
            final String pc = PluginJSonUtils.getJsonValue(br, "page_count");
            final int currentPage = cp != null ? Integer.parseInt(cp) : 1;
            final int pageCount = pc != null ? Integer.parseInt(pc) : 1;
            String parent = null;

            fpName = PluginJSonUtils.getJsonValue(this.br, "name");
            final String main_folderid = new Regex(cryptedlink, "box\\.com/(s|shared)/([a-z0-9]+)").getMatch(1);
            String json_Text = br.getRegex("\"db\":(\\{.*?\\})\\}\\}").getMatch(0);
            if (json_Text == null) {
                /*
                 * Check if folder is empty - folders that contain only subfolders but no files are also "empty" so better check this in
                 * here!
                 */
                if (br.containsHTML("class=\"empty_folder\"")) {
                    decryptedLinks.add(createOfflinelink(cryptedlink));
                    return decryptedLinks;
                }
                /* Maybe single file */
                String filename = br.getRegex("data-hover=\"tooltip\" aria-label=\"([^<>\"]*?)\"").getMatch(0);
                if (filename == null) {
                    filename = br.getRegex("var name = '([^<>\"]*?)';").getMatch(0);
                }
                final String filesize = br.getRegex("class=\"file_size\">\\(([^<>\"]*?)\\)</span>").getMatch(0);
                String fid = br.getRegex("itemTypedID: \"f_(\\d+)\"").getMatch(0);
                if (filename != null) {
                    boolean web_doc = false;
                    if (fid == null) {
                        fid = Long.toString(System.currentTimeMillis());
                        web_doc = true;
                    }
                    final DownloadLink fina = createDownloadlink("https://app.box.com/index.php?rm=box_download_shared_file" + "&file_id=f_" + fid + "&shared_name=" + main_folderid);
                    fina.setName(Encoding.htmlDecode(filename.trim()));
                    if (filesize != null) {
                        fina.setDownloadSize(SizeFormatter.getSize(filesize));
                    }
                    fina.setAvailable(true);
                    if (web_doc) {
                        fina.setProperty("force_http_download", true);
                    }
                    fina.setProperty("mainlink", parameter);
                    decryptedLinks.add(fina);
                    return decryptedLinks;
                }
                // new failover
                final DownloadLink dl = createDownloadlink("https://www.boxdecrypted.com/shared/" + new Regex(cryptedlink, "([a-z0-9]+)$").getMatch(0));
                decryptedLinks.add(dl);
                return decryptedLinks;
                // /* Okay seems like our code failed */
                // logger.warning("Decrypt failed for link: " + cryptedlink);
                // return null;
            }
            final String url = br.getURL();
            for (int i = currentPage; i - 1 != pageCount; i++) {
                final ArrayList<String> filelinkinfo = splitAtRepeat(json_Text);
                if (filelinkinfo.isEmpty()) {
                    // this can happen when empty folder we need to seek info from javascript based html instead of pure json.

                    json_Text = br.getRegex("(\"shared_folder_info\".*?),\"db\"").getMatch(0);
                    // fix packagename
                    final String parentName = PluginJSonUtils.getJsonValue(json_Text, "name");
                    if (fpName == null || parentName != null && !fpName.equals(parentName)) {
                        fpName = parentName;
                    }
                    decryptedLinks.add(createOfflinelink(cryptedlink));
                    break;
                }
                for (final String singleflinkinfo : filelinkinfo) {
                    // each json object has parent and parent name info. We can use this to set correct packagename!
                    if (parent == null) {
                        parent = PluginJSonUtils.getJsonValue(singleflinkinfo, "parent");
                    }
                    final String parentName = PluginJSonUtils.getJsonValue(singleflinkinfo, "parent_name");
                    if (fpName == null || parentName != null && !fpName.equals(parentName)) {
                        fpName = parentName;
                    }
                    final String type = PluginJSonUtils.getJsonValue(singleflinkinfo, "type");
                    /* Check for invalid entry */
                    if (type == null) {
                        continue;
                    }
                    final String id = new Regex(singleflinkinfo, "\"typed_id\":\"(f|d)_(\\d+)\"").getMatch(1);
                    if (type.equals("folder")) {
                        final DownloadLink fina = createDownloadlink("https://app.box.com/s/" + main_folderid + "/1/" + id);
                        decryptedLinks.add(fina);
                    } else {
                        final String filename = PluginJSonUtils.getJsonValue(singleflinkinfo, "name");
                        final String filesize = PluginJSonUtils.getJsonValue(singleflinkinfo, "raw_size");
                        final String sha1 = PluginJSonUtils.getJsonValue(singleflinkinfo, "sha1");
                        if (id != null && filename != null && filesize != null) {
                            final String finallink = "https://app.box.com/index.php?rm=box_download_shared_file" + "&file_id=f_" + id + "&shared_name=" + main_folderid;
                            final DownloadLink fina = createDownloadlink(finallink);
                            fina.setName(filename);
                            fina.setDownloadSize(Long.parseLong(filesize));
                            if (sha1 != null) {
                                fina.setSha1Hash(sha1);
                            }
                            fina.setAvailable(true);

                            fina.setContentUrl(finallink);

                            decryptedLinks.add(fina);
                        }
                    }
                }
                if (i != pageCount) {
                    if (isAbort()) {
                        break;
                    } else {
                        br.getPage(url + "/" + (i + 1) + "/" + parent);
                        json_Text = br.getRegex("\"db\":(\\{.*?\\})\\}\\}").getMatch(0);
                        if (json_Text == null) {
                            break;
                        }
                    }
                }
            }

            if (decryptedLinks.size() == 0) {
                if (br.containsHTML("class=\"empty_folder\"")) {
                    decryptedLinks.add(createOfflinelink(cryptedlink));
                    return decryptedLinks;
                }
                logger.warning("Decrypt failed for link: " + cryptedlink);
                return null;
            }
            if (fpName != null) {
                final FilePackage fp = FilePackage.getInstance();
                fp.setName(Encoding.htmlDecode(fpName.trim()));
                fp.addLinks(decryptedLinks);
            }
            return decryptedLinks;
        }
        if (br.containsHTML("id=\"name\" title=\"boxdocs\"")) {
            fpName = new Regex(cryptedlink, "([a-z0-9]+)$").getMatch(0);
            final String textArea = br.getRegex("<tr id=\"wrp_base\" style=\"height: 100%;\">(.*?)<tr id=\"wrp_footer\">").getMatch(0);
            if (textArea == null) {
                logger.warning("Decrypt failed for link: " + cryptedlink);
                return null;
            }
            final String[] pictureLinks = HTMLParser.getHttpLinks(textArea, "");
            if (pictureLinks != null && pictureLinks.length != 0) {
                for (final String pictureLink : pictureLinks) {
                    if (!pictureLink.contains("box.com/")) {
                        final DownloadLink dl = createDownloadlink("directhttp://" + pictureLink);
                        decryptedLinks.add(dl);
                    }
                }
            }
        } else {
            // Folder or single link
            if (br.containsHTML("id=\"shared_folder_files_tab_content\"")) {
                br.getRequest().setHtmlCode(br.toString().replace("\\", ""));
                final String pathValue = br.getRegex("\\{\"p_(\\d+)\"").getMatch(0);
                fpName = br.getRegex("\"name\":\"([^<>\"]*?)\"").getMatch(0);
                // Hmm maybe a single link
                if (pathValue == null || fpName == null) {
                    decryptedLinks.add(createSingleDownloadlink(cryptedlink));
                    return decryptedLinks;
                }
                final String basicLink = br.getURL().replace("/shared/", "/s/");
                final String pageCount = br.getRegex("\"page_count\":(\\d+)").getMatch(0);
                final String linkID = new Regex(cryptedlink, "box\\.com/shared/([a-z0-9]+)").getMatch(0);
                int pages = 1;
                if (pageCount != null) {
                    pages = Integer.parseInt(pageCount);
                }
                for (int i = 1; i <= pages; i++) {
                    logger.info("Decrypting page " + i + " of " + pages);
                    br.getPage(basicLink + "/" + i + "/" + pathValue);
                    br.getRequest().setHtmlCode(br.toString().replace("\\", ""));
                    final String[] links = br.getRegex("\"nnttttdata-href=\"(/s/[a-z0-9]+/\\d+/\\d+/\\d+/\\d+)\"").getColumn(0);
                    final String[] filenames = br.getRegex("data-downloadurl=\"[a-z0-9/]+:([^<>\"]*?):https://www\\.box").getColumn(0);
                    final String[] filesizes = br.getRegex("class=\"item_size\">([^<>\"]*?)</li>").getColumn(0);
                    final String[] folderLinks = br.getRegex("\"unidb\":\"folder_(\\d+)\"").getColumn(0);
                    // Hmm maybe a single link
                    if ((links == null || links.length == 0 || filenames == null || filenames.length == 0 || filesizes == null || filesizes.length == 0) && (folderLinks == null || folderLinks.length == 0)) {
                        decryptedLinks.add(createSingleDownloadlink(cryptedlink));
                        return decryptedLinks;
                    }
                    if (folderLinks != null && folderLinks.length != 0) {
                        for (final String folderLink : folderLinks) {
                            final DownloadLink dl = createDownloadlink("https://www.box.com/shared/" + linkID + "/1/" + folderLink);
                            decryptedLinks.add(dl);
                        }
                    }
                    if (links != null && links.length != 0 && filenames != null && filenames.length != 0 && filesizes != null && filesizes.length != 0) {
                        final int numberOfEntries = links.length;
                        if (filenames.length != numberOfEntries || filesizes.length != numberOfEntries) {
                            logger.warning("Decrypt failed for link: " + cryptedlink);
                            return null;
                        }
                        for (int count = 0; count < numberOfEntries; count++) {
                            final String url = links[count];
                            final String filename = filenames[count];
                            final String filesize = filesizes[count];
                            final DownloadLink dl = createDownloadlink("https://www.boxdecrypted.com" + url);
                            dl.setProperty("plainfilename", filename);
                            dl.setProperty("plainfilesize", filesize);
                            dl.setName(Encoding.htmlDecode(filename.trim()));
                            dl.setDownloadSize(SizeFormatter.getSize(filesize));
                            dl.setAvailable(true);
                            decryptedLinks.add(dl);
                        }
                    }
                }
            } else {
                final DownloadLink dl = createDownloadlink("https://www.boxdecrypted.com/shared/" + new Regex(cryptedlink, "([a-z0-9]+)$").getMatch(0));
                decryptedLinks.add(dl);
                return decryptedLinks;
            }

        }
        if (fpName != null) {
            final FilePackage fp = FilePackage.getInstance();
            fp.setName(Encoding.htmlDecode(fpName.trim()));
            fp.addLinks(decryptedLinks);
        }
        if (decryptedLinks.size() == 0) {
            logger.info("Haven't found any links to decrypt, now trying decryptSingleDLPage");
            decryptedLinks.add(createDownloadlink(cryptedlink.replaceAll("box\\.com/shared", "boxdecrypted.com/shared")));
        }
        return decryptedLinks;
    }

    private DownloadLink createSingleDownloadlink(final String cryptedLink) {
        final String fileID = br.getRegex("\"typed_id\":\"f_(\\d+)\"").getMatch(0);
        final String fid = new Regex(cryptedLink, "([a-z0-9]+)$").getMatch(0);
        String fsize = br.getRegex("\"size\":\"([^<>\"]*?)\"").getMatch(0);
        if (fsize == null) {
            fsize = "0b";
        }
        String singleFilename = br.getRegex("id=\"filename_\\d+\" name=\"([^<>\"]*?)\"").getMatch(0);
        if (singleFilename == null) {
            singleFilename = fid;
        }
        final DownloadLink dl = createDownloadlink("http://www.boxdecrypted.com/s/" + fid + "/1/1/1/" + new Random().nextInt(1000));
        dl.setProperty("plainfilename", singleFilename);
        dl.setProperty("plainfilesize", fsize);
        if (fileID != null) {
            dl.setProperty("fileid", fileID);
        }
        dl.setProperty("sharedname", fid);
        return dl;
    }

    /**
     * cant always make regex that works for getColumn or getRow. this following method works around this!
     *
     * @author raztoki
     * @param input
     * @return
     */
    private ArrayList<String> splitAtRepeat(final String input) {
        String i = input;
        ArrayList<String> filelinkinfo = new ArrayList<String>();
        while (true) {
            String result = new Regex(i, "(\"(?:file|folder)_\\d+.*?\\}),(?:\"(?:file|folder)_\\d+|)").getMatch(0);
            if (result == null) {
                break;
            }
            result = PluginJSonUtils.validateResultForArrays(input, result);
            filelinkinfo.add(result);
            i = i.replace(result, "");
        }

        return filelinkinfo;
    }

    /**
     * Extracts the download link from a single file download page.
     *
     * @param cryptedUrl
     *            the url of the download page
     * @return a list that contains the extracted download link, null if the download link couldn't be extracted.
     * @throws IOException
     */

    /**
     * Extracts download links from a box.net rss feed.
     *
     * @param feedUrl
     *            the url of the rss feed
     * @return a list of decrypted links, null if the links could not be extracted.
     * @throws IOException
     */
    private ArrayList<DownloadLink> decryptFeed(String feedUrl, final String cryptedlink) throws IOException {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        if (br.getRedirectLocation() != null) {
            br.getPage(br.getRedirectLocation());
        }
        if (br.containsHTML("<title>Box \\| 404 Page Not Found</title>")) {
            final DownloadLink dl = createDownloadlink(cryptedlink.replaceAll("box\\.com/shared", "boxdecrypted.com/shared"));
            dl.setAvailable(false);
            dl.setProperty("offline", true);
            decryptedLinks.add(dl);
            return decryptedLinks;
        }
        String title = br.getRegex(FEED_FILETITLE_PATTERN).getMatch(0);
        String[] folder = br.getRegex(FEED_FILEINFO_PATTERN).getColumn(0);
        if (folder == null) {
            return null;
        }
        for (String fileInfo : folder) {
            String dlUrl = new Regex(fileInfo, FEED_DL_LINK_PATTERN).getMatch(0);
            if (dlUrl == null) {
                logger.info("Couldn't find download link. Skipping file.");
                continue;
            }
            // These ones are direct links so let's handle them as directlinks^^
            dlUrl = "directhttp://" + dlUrl.replace("amp;", "");
            logger.finer("Found link in rss feed: " + dlUrl);
            DownloadLink dl = createDownloadlink(dlUrl);
            decryptedLinks.add(dl);
            if (title != null) {
                FilePackage filePackage = FilePackage.getInstance();
                filePackage.setName(title);
                filePackage.add(dl);
            }
        }
        logger.info("Found " + decryptedLinks.size() + " links in feed: " + feedUrl);
        return decryptedLinks;
    }

    /* NO OVERRIDE!! */
    public boolean hasCaptcha(CryptedLink link, jd.plugins.Account acc) {
        return false;
    }

}