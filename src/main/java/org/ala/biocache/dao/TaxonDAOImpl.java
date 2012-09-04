package org.ala.biocache.dao;


import au.org.ala.biocache.IndexDAO;
import au.org.ala.biocache.SolrIndexDAO;
import org.ala.biocache.util.DownloadFields;
import org.apache.commons.lang.ArrayUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

@Component("taxonDao")
public class TaxonDAOImpl implements TaxonDAO {

    private static final Logger logger = Logger.getLogger(TaxonDAOImpl.class);
    protected SolrServer server;

    /**
     * Initialise the SOLR server instance
     */
    public TaxonDAOImpl() {
        if (this.server == null) {
            try {
                //use the solr server that has been in the biocache-store...
                SolrIndexDAO dao = (SolrIndexDAO) au.org.ala.biocache.Config.getInstance(IndexDAO.class);
                dao.init();
                server = dao.solrServer();
            } catch (Exception ex) {
                logger.error("Error initialising embedded SOLR server: " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void extractHierarchy(String q, String[] fq, Writer writer) throws Exception {
        try {
            List<FacetField.Count> kingdoms = extractFacet(q,fq, "kingdom");
            for(FacetField.Count k: kingdoms){
                outputNestedLayerStart(k.getName(), writer);
                List<FacetField.Count> phyla = extractFacet(q, (String[]) ArrayUtils.add(fq, "kingdom:" + k.getName()), "phylum");
                for(FacetField.Count p: phyla){
                    outputNestedMappableLayerStart("phylum", p.getName(), writer);
                    List<FacetField.Count> classes = extractFacet(q, (String[]) ArrayUtils.add(fq, "phylum:" + p.getName()), "class");
                    for(FacetField.Count c: classes){
                        outputNestedMappableLayerStart("class", c.getName(), writer);
                        List<FacetField.Count> orders = extractFacet(q, (String[])ArrayUtils.add(fq, "class:"+c.getName()), "order");
                        for(FacetField.Count o: orders){
                            outputNestedMappableLayerStart("order", o.getName(), writer);
                            List<FacetField.Count> families = extractFacet(q, (String[])ArrayUtils.add(fq, "order:"+o.getName()), "family");
                            for(FacetField.Count f: families){
                                outputNestedMappableLayerStart("family", f.getName(), writer);
                                List<FacetField.Count> genera = extractFacet(q, (String[])ArrayUtils.add(fq, "family:"+f.getName()), "genus");
                                for(FacetField.Count g: genera){
                                    outputNestedMappableLayerStart("genus", g.getName(), writer);
                                    List<FacetField.Count> species = extractFacet(q, (String[])ArrayUtils.add(fq, "genus:"+g.getName()), "species");
                                    for(FacetField.Count s: species){
                                        outputLayer("species", s.getName(), writer);
                                    }
                                    outputNestedLayerEnd(writer);
                                }
                                outputNestedLayerEnd(writer);
                            }
                            outputNestedLayerEnd(writer);
                        }
                        outputNestedLayerEnd(writer);
                    }
                    outputNestedLayerEnd(writer);
                }
                outputNestedLayerEnd(writer);
            }
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    void outputNestedMappableLayerStart(String rank, String taxon, Writer out) throws Exception {
        out.write("<Layer queryable=\"1\"><Name>" + rank + ":" + taxon + "</Name><Title>" + taxon + "</Title>");
        out.flush();
    }

    void outputNestedLayerStart(String layerName, Writer out) throws Exception {
        out.write("<Layer><Name>"+layerName + "</Name><Title>"+layerName + "</Title>\n\t");
        out.flush();
    }

    void outputNestedLayerEnd(Writer out) throws Exception {
        out.write("</Layer>");
        out.flush();
    }

    void outputLayer(String rank, String taxon, Writer out) throws Exception {
        String normalised = taxon.replaceFirst("\\([A-Za-z]*\\) ", "").replace(" ", "_"); //remove the subgenus, replace spaces with underscores
        out.write("<Layer queryable=\"1\"><Name>" + rank + ":" + normalised + "</Name><Title>" + taxon + "</Title></Layer>");
        out.flush();
    }

    private List<FacetField.Count> extractFacet(String queryString, String[] filterQueries, String facetName) throws Exception {
        SolrQuery query = new SolrQuery(queryString);
        query.setFacet(true);
        query.addFacetField(facetName);
        query.setRows(0);
        query.setFacetLimit(200000);
        query.setStart(0);
        query.setFacetMinCount(1);
        query.setFacetSort("index");
        //query.setFacet
        if(filterQueries != null){
            for(String fq: filterQueries) query.addFilterQuery(fq);
        }
        QueryResponse response = server.query(query);
        List<FacetField.Count> fc =  response.getFacetField(facetName).getValues();
        if(fc==null) fc = new ArrayList<FacetField.Count>();
        return fc;
    }
}