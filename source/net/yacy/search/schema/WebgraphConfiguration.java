/**
 *  WebgraphConfiguration
 *  Copyright 2011 by Michael Peter Christen
 *  First released 14.04.2011 at http://yacy.net
 *
 *  $LastChangedDate: 2011-04-14 22:05:04 +0200 (Do, 14 Apr 2011) $
 *  $LastChangedRevision: 7654 $
 *  $LastChangedBy: orbiter $
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.search.schema;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;

import net.yacy.cora.document.ASCII;
import net.yacy.cora.federate.solr.ProcessType;
import net.yacy.cora.federate.solr.SchemaConfiguration;
import net.yacy.cora.federate.solr.SchemaDeclaration;
import net.yacy.cora.federate.solr.connector.AbstractSolrConnector;
import net.yacy.cora.federate.solr.connector.SolrConnector;
import net.yacy.cora.protocol.Domains;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.cora.util.CommonPattern;
import net.yacy.document.parser.html.ImageEntry;
import net.yacy.kelondro.data.citation.CitationReference;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.rwi.IndexCell;
import net.yacy.search.index.Segment;

public class WebgraphConfiguration extends SchemaConfiguration implements Serializable {

    private static final long serialVersionUID=-499100932212840385L;

    /**
     * initialize with an empty ConfigurationSet which will cause that all the index
     * attributes are used
     */
    public WebgraphConfiguration(boolean lazy) {
        super();
        this.lazy = lazy;
    }
    
    /**
     * initialize the schema with a given configuration file
     * the configuration file simply contains a list of lines with keywords
     * or keyword = value lines (while value is a custom Solr field name
     * @param configurationFile
     * @throws IOException 
     */
    public WebgraphConfiguration(final File configurationFile, boolean lazy) throws IOException {
        super(configurationFile);
        this.lazy = lazy;
        // check consistency: compare with YaCyField enum
        if (this.isEmpty()) return;
        Iterator<Entry> it = this.entryIterator();
        for (SchemaConfiguration.Entry etr = it.next(); it.hasNext(); etr = it.next()) {
            try {
                WebgraphSchema f = WebgraphSchema.valueOf(etr.key());
                f.setSolrFieldName(etr.getValue());
            } catch (IllegalArgumentException e) {
                Log.logFine("SolrWebgraphWriter", "solr schema file " + configurationFile.getAbsolutePath() + " defines unknown attribute '" + etr.toString() + "'");
                it.remove();
            }
        }
        // check consistency the other way: look if all enum constants in SolrField appear in the configuration file
        for (SchemaDeclaration field: WebgraphSchema.values()) {
            if (this.get(field.name()) == null) {
                Log.logWarning("SolrWebgraphWriter", " solr schema file " + configurationFile.getAbsolutePath() + " is missing declaration for '" + field.name() + "'");
            }
        }
    }
    
    public static class Subgraph {
        public final ArrayList<String>[] urlProtocols, urlStubs;
        public final ArrayList<SolrInputDocument> edges;
        @SuppressWarnings("unchecked")
        public Subgraph(int inboundSize, int outboundSize) {
            this.urlProtocols = new ArrayList[]{new ArrayList<String>(inboundSize), new ArrayList<String>(outboundSize)};
            this.urlStubs = new ArrayList[]{new ArrayList<String>(inboundSize), new ArrayList<String>(outboundSize)};
            this.edges = new ArrayList<SolrInputDocument>(inboundSize + outboundSize);
        }
    }
    
    public Subgraph edges(
            final DigestURI source, final ResponseHeader responseHeader, String[] collections, int clickdepth_source,
            final Map<DigestURI, Properties> alllinks,
            final Map<DigestURI, ImageEntry> images,
            final Set<DigestURI> inboundLinks,
            final Set<DigestURI> outboundLinks,
            IndexCell<CitationReference> citations
            ) {
        boolean allAttr = this.isEmpty();
        Subgraph subgraph = new Subgraph(inboundLinks.size(), outboundLinks.size());
        addEdges(
                subgraph, source, responseHeader, collections, clickdepth_source,
                allAttr, alllinks, images, true, inboundLinks, citations);
        addEdges(
                subgraph, source, responseHeader, collections, clickdepth_source,
                allAttr, alllinks, images, false, outboundLinks, citations);
        return subgraph;
    }
    
    private void addEdges(
            final Subgraph subgraph,
            final DigestURI source, final ResponseHeader responseHeader, String[] collections, int clickdepth_source,
            final boolean allAttr, final Map<DigestURI, Properties> alllinks, final Map<DigestURI, ImageEntry> images,
            final boolean inbound, final Set<DigestURI> links,
            final IndexCell<CitationReference> citations) {
        for (final DigestURI target_url: links) {

            Set<ProcessType> processTypes = new LinkedHashSet<ProcessType>();
            
            final Properties p = alllinks.get(target_url);
            if (p == null) continue;
            final String name = p.getProperty("name", ""); // the name attribute
            final String text = p.getProperty("text", ""); // the text between the <a></a> tag
            final String rel = p.getProperty("rel", "");   // the rel-attribute
            int ioidx = inbound ? 0 : 1;
            
            // index organization
            StringBuilder idi = new StringBuilder(8);
            idi.append(Integer.toHexString((name + text + rel).hashCode()).toLowerCase());
            while (idi.length() < 8) idi.insert(0, '0');
            String source_id = ASCII.String(source.hash());
            String target_id = ASCII.String(target_url.hash());
            StringBuilder id = new StringBuilder(source_id).append(target_id).append(idi);
            SolrInputDocument edge = new SolrInputDocument();
            add(edge, WebgraphSchema.id, id.toString());
            if (allAttr || contains(WebgraphSchema.load_date_dt)) {
                Date loadDate = new Date();
                Date modDate = responseHeader == null ? new Date() : responseHeader.lastModified();
                if (modDate.getTime() > loadDate.getTime()) modDate = loadDate;
                add(edge, WebgraphSchema.load_date_dt, loadDate);
            }
            if (allAttr || contains(WebgraphSchema.last_modified)) add(edge, WebgraphSchema.last_modified, responseHeader == null ? new Date() : responseHeader.lastModified());
            add(edge, WebgraphSchema.collection_sxt, collections);

            // add the source attributes
            add(edge, WebgraphSchema.source_id_s, source_id);
            final String source_url_string = source.toNormalform(false);
            int pr_source = source_url_string.indexOf("://",0);
            if (allAttr || contains(WebgraphSchema.source_protocol_s)) add(edge, WebgraphSchema.source_protocol_s, source_url_string.substring(0, pr_source));
            if (allAttr || contains(WebgraphSchema.source_urlstub_s)) add(edge, WebgraphSchema.source_urlstub_s, source_url_string.substring(pr_source + 3));
            Map<String, String> source_searchpart = source.getSearchpartMap();
            if (source_searchpart == null) {
                if (allAttr || contains(WebgraphSchema.source_parameter_count_i)) add(edge, WebgraphSchema.source_parameter_count_i, 0);
            } else {
                if (allAttr || contains(WebgraphSchema.source_parameter_count_i)) add(edge, WebgraphSchema.source_parameter_count_i, source_searchpart.size());
                if (allAttr || contains(WebgraphSchema.source_parameter_key_sxt)) add(edge, WebgraphSchema.source_parameter_key_sxt, source_searchpart.keySet().toArray(new String[source_searchpart.size()]));
                if (allAttr || contains(WebgraphSchema.source_parameter_value_sxt)) add(edge, WebgraphSchema.source_parameter_value_sxt, source_searchpart.values().toArray(new String[source_searchpart.size()]));
            }
            if (allAttr || contains(WebgraphSchema.source_chars_i)) add(edge, WebgraphSchema.source_chars_i, source_url_string.length());
            String source_host = null;
            if ((source_host = source.getHost()) != null) {
                String dnc = Domains.getDNC(source_host);
                String subdomOrga = source_host.length() - dnc.length() <= 0 ? "" : source_host.substring(0, source_host.length() - dnc.length() - 1);
                int pp = subdomOrga.lastIndexOf('.');
                String subdom = (pp < 0) ? "" : subdomOrga.substring(0, pp);
                String orga = (pp < 0) ? subdomOrga : subdomOrga.substring(pp + 1);
                if (allAttr || contains(WebgraphSchema.source_host_s)) add(edge, WebgraphSchema.source_host_s, source_host);
                if (allAttr || contains(WebgraphSchema.source_host_id_s)) add(edge, WebgraphSchema.source_host_id_s, source.hosthash());
                if (allAttr || contains(WebgraphSchema.source_host_dnc_s)) add(edge, WebgraphSchema.source_host_dnc_s, dnc);
                if (allAttr || contains(WebgraphSchema.source_host_organization_s)) add(edge, WebgraphSchema.source_host_organization_s, orga);
                if (allAttr || contains(WebgraphSchema.source_host_organizationdnc_s)) add(edge, WebgraphSchema.source_host_organizationdnc_s, orga + '.' + dnc);
                if (allAttr || contains(WebgraphSchema.source_host_subdomain_s)) add(edge, WebgraphSchema.source_host_subdomain_s, subdom);
            }
            if (allAttr || contains(WebgraphSchema.source_file_ext_s)) add(edge, WebgraphSchema.source_file_ext_s, source.getFileExtension());
            if (allAttr || contains(WebgraphSchema.source_path_s)) add(edge, WebgraphSchema.source_path_s, source.getPath());
            if (allAttr || contains(WebgraphSchema.source_path_folders_count_i) || contains(WebgraphSchema.source_path_folders_sxt)) {
                String[] paths = source.getPaths();
                add(edge, WebgraphSchema.source_path_folders_count_i, paths.length);
                add(edge, WebgraphSchema.source_path_folders_sxt, paths);
            }
            if (this.contains(WebgraphSchema.source_protocol_s) && this.contains(WebgraphSchema.source_urlstub_s) && this.contains(WebgraphSchema.source_id_s)) {
                add(edge, WebgraphSchema.source_clickdepth_i, clickdepth_source);
                if (clickdepth_source < 0 || clickdepth_source > 1) processTypes.add(ProcessType.CLICKDEPTH);
            }
            
            // add the source attributes about the target
            if (allAttr || contains(WebgraphSchema.target_inbound_b)) add(edge, WebgraphSchema.target_inbound_b, inbound);
            if (allAttr || contains(WebgraphSchema.target_name_t)) add(edge, WebgraphSchema.target_name_t, name.length() > 0 ? name : "");
            if (allAttr || contains(WebgraphSchema.target_rel_s)) add(edge, WebgraphSchema.target_rel_s, rel.length() > 0 ? rel : "");
            if (allAttr || contains(WebgraphSchema.target_relflags_i)) add(edge, WebgraphSchema.target_relflags_i, relEval(rel.length() > 0 ? rel : ""));
            if (allAttr || contains(WebgraphSchema.target_linktext_t)) add(edge, WebgraphSchema.target_linktext_t, text.length() > 0 ? text : "");
            if (allAttr || contains(WebgraphSchema.target_linktext_charcount_i)) add(edge, WebgraphSchema.target_linktext_charcount_i, text.length());
            if (allAttr || contains(WebgraphSchema.target_linktext_wordcount_i)) add(edge, WebgraphSchema.target_linktext_wordcount_i, text.length() > 0 ? CommonPattern.SPACE.split(text).length : 0);
            ImageEntry ientry = images.get(target_url);
            String alttext = ientry == null ? "" : ientry.alt();
            if (allAttr || contains(WebgraphSchema.target_alt_t)) add(edge, WebgraphSchema.target_alt_t, alttext);
            if (allAttr || contains(WebgraphSchema.target_alt_charcount_i)) add(edge, WebgraphSchema.target_alt_charcount_i, alttext.length());
            if (allAttr || contains(WebgraphSchema.target_alt_wordcount_i)) add(edge, WebgraphSchema.target_alt_wordcount_i, alttext.length() > 0 ? CommonPattern.SPACE.split(alttext).length : 0);
            
            // add the target attributes            
            add(edge, WebgraphSchema.target_id_s, target_id);
            final String target_url_string = target_url.toNormalform(false);
            int pr_target = target_url_string.indexOf("://",0);
            subgraph.urlProtocols[ioidx].add(target_url_string.substring(0, pr_target));
            if (allAttr || contains(WebgraphSchema.target_protocol_s)) add(edge, WebgraphSchema.target_protocol_s, target_url_string.substring(0, pr_target));
            subgraph.urlStubs[ioidx].add(target_url_string.substring(pr_target + 3));
            if (allAttr || contains(WebgraphSchema.target_urlstub_s)) add(edge, WebgraphSchema.target_urlstub_s, target_url_string.substring(pr_target + 3));
            Map<String, String> target_searchpart = target_url.getSearchpartMap();
            if (target_searchpart == null) {
                if (allAttr || contains(WebgraphSchema.target_parameter_count_i)) add(edge, WebgraphSchema.target_parameter_count_i, 0);
            } else {
                if (allAttr || contains(WebgraphSchema.target_parameter_count_i)) add(edge, WebgraphSchema.target_parameter_count_i, target_searchpart.size());
                if (allAttr || contains(WebgraphSchema.target_parameter_key_sxt)) add(edge, WebgraphSchema.target_parameter_key_sxt, target_searchpart.keySet().toArray(new String[target_searchpart.size()]));
                if (allAttr || contains(WebgraphSchema.target_parameter_value_sxt)) add(edge, WebgraphSchema.target_parameter_value_sxt,  target_searchpart.values().toArray(new String[target_searchpart.size()]));
            }
            if (allAttr || contains(WebgraphSchema.target_chars_i)) add(edge, WebgraphSchema.target_chars_i, target_url_string.length());
            String target_host = null;
            if ((target_host = target_url.getHost()) != null) {
                String dnc = Domains.getDNC(target_host);
                String subdomOrga = target_host.length() - dnc.length() <= 0 ? "" : target_host.substring(0, target_host.length() - dnc.length() - 1);
                int pp = subdomOrga.lastIndexOf('.');
                String subdom = (pp < 0) ? "" : subdomOrga.substring(0, pp);
                String orga = (pp < 0) ? subdomOrga : subdomOrga.substring(pp + 1);
                if (allAttr || contains(WebgraphSchema.target_host_s)) add(edge, WebgraphSchema.target_host_s, target_host);
                if (allAttr || contains(WebgraphSchema.target_host_id_s)) add(edge, WebgraphSchema.target_host_id_s, target_url.hosthash());
                if (allAttr || contains(WebgraphSchema.target_host_dnc_s)) add(edge, WebgraphSchema.target_host_dnc_s, dnc);
                if (allAttr || contains(WebgraphSchema.target_host_organization_s)) add(edge, WebgraphSchema.target_host_organization_s, orga);
                if (allAttr || contains(WebgraphSchema.target_host_organizationdnc_s)) add(edge, WebgraphSchema.target_host_organizationdnc_s, orga + '.' + dnc);
                if (allAttr || contains(WebgraphSchema.target_host_subdomain_s)) add(edge, WebgraphSchema.target_host_subdomain_s, subdom);
            }
            if (allAttr || contains(WebgraphSchema.target_file_ext_s)) add(edge, WebgraphSchema.target_file_ext_s, target_url.getFileExtension());
            if (allAttr || contains(WebgraphSchema.target_path_s)) add(edge, WebgraphSchema.target_path_s, target_url.getPath());
            if (allAttr || contains(WebgraphSchema.target_path_folders_count_i) || contains(WebgraphSchema.target_path_folders_sxt)) {
                String[] paths = target_url.getPaths();
                add(edge, WebgraphSchema.target_path_folders_count_i, paths.length);
                add(edge, WebgraphSchema.target_path_folders_sxt, paths);
            }

            if (this.contains(WebgraphSchema.target_protocol_s) && this.contains(WebgraphSchema.target_urlstub_s) && this.contains(WebgraphSchema.target_id_s)) {
                if ((allAttr || contains(WebgraphSchema.target_clickdepth_i)) && citations != null) {
                    if (target_url.probablyRootURL()) {
                        boolean lc = this.lazy; this.lazy = false;
                        add(edge, WebgraphSchema.target_clickdepth_i, 0);
                        this.lazy = lc;
                    } else {
                        add(edge, WebgraphSchema.target_clickdepth_i, 999);
                        processTypes.add(ProcessType.CLICKDEPTH); // postprocessing needed; this is also needed if the depth is positive; there could be a shortcut
                    }
                }
            }
            
            if (allAttr || contains(WebgraphSchema.process_sxt)) {
                List<String> pr = new ArrayList<String>();
                for (ProcessType t: processTypes) pr.add(t.name());
                add(edge, WebgraphSchema.process_sxt, pr);
            }
            
            // add the edge to the subgraph
            subgraph.edges.add(edge);
        }
    }
    
    public void postprocessing(Segment segment) {
        if (!this.contains(WebgraphSchema.process_sxt)) return;
        if (!segment.connectedCitation()) return;
        SolrConnector connector = segment.fulltext().getWebgraphConnector();
        // that means we must search for those entries.
        connector.commit(true); // make sure that we have latest information that can be found
        //BlockingQueue<SolrDocument> docs = index.fulltext().getSolr().concurrentQuery("*:*", 0, 1000, 60000, 10);
        BlockingQueue<SolrDocument> docs = connector.concurrentDocumentsByQuery(WebgraphSchema.process_sxt.getSolrFieldName() + ":[* TO *]", 0, 100000, 60000, 50);
        
        SolrDocument doc;
        String protocol, urlstub, id;
        DigestURI url;
        int proccount = 0, proccount_clickdepthchange = 0, proccount_referencechange = 0;
        try {
            while ((doc = docs.take()) != AbstractSolrConnector.POISON_DOCUMENT) {
                // for each to-be-processed entry work on the process tag
                Collection<Object> proctags = doc.getFieldValues(WebgraphSchema.process_sxt.getSolrFieldName());
                for (Object tag: proctags) {
                    
                    try {
                        SolrInputDocument sid = this.toSolrInputDocument(doc);
                        
                        // switch over tag types
                        ProcessType tagtype = ProcessType.valueOf((String) tag);
                        if (tagtype == ProcessType.CLICKDEPTH) {
                            if (this.contains(WebgraphSchema.source_protocol_s) && this.contains(WebgraphSchema.source_urlstub_s) && this.contains(WebgraphSchema.source_id_s)) {
                                protocol = (String) doc.getFieldValue(WebgraphSchema.source_protocol_s.getSolrFieldName());
                                urlstub = (String) doc.getFieldValue(WebgraphSchema.source_urlstub_s.getSolrFieldName());
                                id = (String) doc.getFieldValue(WebgraphSchema.source_id_s.getSolrFieldName());
                                url = new DigestURI(protocol + "://" + urlstub, ASCII.getBytes(id));
                                if (postprocessing_clickdepth(segment, doc, sid, url, WebgraphSchema.source_clickdepth_i)) proccount_clickdepthchange++;
                            }
                            if (this.contains(WebgraphSchema.target_protocol_s) && this.contains(WebgraphSchema.target_urlstub_s) && this.contains(WebgraphSchema.target_id_s)) {
                                protocol = (String) doc.getFieldValue(WebgraphSchema.target_protocol_s.getSolrFieldName());
                                urlstub = (String) doc.getFieldValue(WebgraphSchema.target_urlstub_s.getSolrFieldName());
                                id = (String) doc.getFieldValue(WebgraphSchema.target_id_s.getSolrFieldName());
                                url = new DigestURI(protocol + "://" + urlstub, ASCII.getBytes(id));
                                if (postprocessing_clickdepth(segment, doc, sid, url, WebgraphSchema.target_clickdepth_i)) proccount_clickdepthchange++;
                            }
                        }
                        
                        // all processing steps checked, remove the processing tag
                        sid.removeField(WebgraphSchema.process_sxt.getSolrFieldName());
                        
                        // send back to index
                        connector.add(sid);
                        proccount++;
                    } catch (Throwable e1) {
                    }
                    
                }
            }
            Log.logInfo("WebgraphConfiguration", "cleanup_processing: re-calculated " + proccount+ " new documents, " + proccount_clickdepthchange + " clickdepth values changed, " + proccount_referencechange + " reference-count values changed.");
        } catch (InterruptedException e) {
        }
    }

    /**
     * encode a string containing attributes from anchor rel properties binary:
     * bit 0: "me" contained in rel
     * bit 1: "nofollow" contained in rel
     * @param rel
     * @return binary encoded information about rel
     */
    private static int relEval(final String rels) {
        int i = 0;
        final String s0 = rels.toLowerCase().trim();
        if ("me".equals(s0)) i += 1;
        if ("nofollow".equals(s0)) i += 2;
        return i;
    }

    /**
     * save configuration to file and update enum SolrFields
     * @throws IOException
     */
    @Override
    public void commit() throws IOException {
        try {
            super.commit();
            // make sure the enum SolrField.SolrFieldName is current
            Iterator<Entry> it = this.entryIterator();
            for (SchemaConfiguration.Entry etr = it.next(); it.hasNext(); etr = it.next()) {
                try {
                    SchemaDeclaration f = WebgraphSchema.valueOf(etr.key());
                    f.setSolrFieldName(etr.getValue());
                } catch (IllegalArgumentException e) {
                    continue;
                }
            }
        } catch (final IOException e) {}
    }
    


    /**
     * Convert a SolrDocument to a SolrInputDocument.
     * This is useful if a document from the search index shall be modified and indexed again.
     * This shall be used as replacement of ClientUtils.toSolrInputDocument because we remove some fields
     * which are created automatically during the indexing process.
     * @param doc the solr document
     * @return a solr input document
     */
    public SolrInputDocument toSolrInputDocument(SolrDocument doc) {
        SolrInputDocument sid = new SolrInputDocument();
        for (String name: doc.getFieldNames()) {
            if (this.contains(name)) { // check each field if enabled in local Solr schema
                sid.addField(name, doc.getFieldValue(name), 1.0f);
            }
        }
        return sid;
    }

}