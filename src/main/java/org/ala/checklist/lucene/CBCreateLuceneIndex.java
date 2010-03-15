
package org.ala.checklist.lucene;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;

import org.apache.lucene.store.FSDirectory;
import org.gbif.ecat.model.ParsedName;
import org.gbif.ecat.parser.NameParser;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.gbif.portal.util.taxonomy.TaxonNameSoundEx;

/**
 * Creates the Lucene index based on the cb_names export generated by org.ala.checklist.ChecklistBankExporter
 * @author Natasha
 */
public class CBCreateLuceneIndex {
    protected Log log = LogFactory.getLog(CBCreateLuceneIndex.class);
    protected ApplicationContext context;
    protected DataSource dataSource;
    protected JdbcTemplate dTemplate;
    //the position in the line for each of the required values
    private final int POS_ID = 0;
    private final int POS_LSID = 2;
    private final int POS_NAME_ID = 3;
    private final int POS_NAME = 4;
    private final int POS_RANK_ID = 6;
    private final int POS_RANK = 7;
    private final int POS_KID = 8;
    private final int POS_K = 9;
    private final int POS_PID = 10;
    private final int POS_P =11;
    private final int POS_CID = 12;
    private final int POS_C = 13;
    private final int POS_OID = 14;
    private final int POS_O =15;
    private final int POS_FID = 16;
    private final int POS_F = 17;
    private final int POS_GID = 18;
    private final int POS_G = 19;
    private final int POS_SID = 20;
    private final int POS_S = 21;
    //Fields that are being indexed or stored in the lucene index
    public enum IndexField{
        NAME(true, false, "name"),
        NAMES(true, false, "names"),
        CLASS(false, true, "class"),
        ID(true, true, "id"),
        RANK(true, true, "rank"),
        SEARCHABLE_NAME(true, false, "searchcan"),
        LSID(false, true, "lsid"),
        KINGDOM(true, true, "kingdom"),
        HOMONYM(true, true, "homonym"),
        PHYLUM(true, true, "phylum"),
        GENUS(true, false, "genus");
        boolean indexed, stored;
        String name;
        IndexField(boolean indexed, boolean stored, String name){
            this.indexed = indexed;
            this.stored = stored;
            this.name = name;
        }
        public boolean isIndexed(){
            return indexed;
        }
        public boolean isStored(){
            return stored;
        }
        public String toString(){
            return name;
        }
    };
     NameParser parser= new NameParser();
     Set<String> knownHomonyms = new HashSet<String>();
    //SQL used to get all the names that are part of the same lexical group
    //private String namesSQL = "select distinct scientific_name as name from name_in_lexical_group nlg JOIN name_string ns ON nlg.name_fk = ns.id JOIN name_usage nu ON nu.lexical_group_fk = nlg.lexical_group_fk where nu.name_fk =?";
     private String namesSQL = "select distinct scientific_name as name from name_in_lexical_group nlg JOIN name_string ns ON nlg.name_fk = ns.id where nlg.lexical_group_fk = (select lexical_group_fk from name_usage where id = ?)";
    private TaxonNameSoundEx tnse;
    public void init() throws Exception{
        String[] locations = {
                     "classpath*:org/ala/**/applicationContext-cb*.xml"
        };
        context = new ClassPathXmlApplicationContext(locations);
        dataSource = (DataSource) context.getBean("cbDataSource");
        dTemplate = new JdbcTemplate(dataSource);
        tnse = new TaxonNameSoundEx();
        //init the known homonyms
        LineIterator lines = new LineIterator(new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResource("org/ala/propertystore/known_homonyms.txt").openStream(), "ISO-8859-1")));
        while (lines.hasNext()) {
            String line = lines.nextLine().trim();
            knownHomonyms.add(line.toUpperCase());
        }
    }
    /**
     * Creates the index from the specified checklist bank names usage export file into
     * the specified index directory.
     * 
     * @param cbExportFile A cb export file as generated from the ChecklistBankExporter
     * @param indexDir
     * @throws Exception
     */
    public void createIndex(String cbExportFile, String indexDir) throws Exception{
        long time = System.currentTimeMillis();
        IndexWriter writer = new IndexWriter(FSDirectory.open(new File(indexDir)), new KeywordAnalyzer(), true, MaxFieldLength.UNLIMITED);
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(cbExportFile), "UTF-8"));
        int unprocessed =0, records=0;
        for(String line =br.readLine(); line != null; line = br.readLine()){
            //process each line in the file
            String[] values = line.split("\\t", 23);
            if(values.length == 22){
                String classification = values[POS_KID] + "|" + values[POS_PID] + "|" + values[POS_CID] + "|" + values[POS_OID] + "|" + values[POS_FID] + "|" + values[POS_GID] + "|" + values[POS_SID];
                String lsid = values[POS_LSID];
                Document doc = buildDocument(values[POS_NAME], classification, values[POS_ID], lsid, values[POS_RANK_ID], values[POS_RANK], values[POS_K], values[POS_P], values[POS_G]);

                //Add the alternate names (these are the names that belong to the same lexical group)
                List<String> lnames = (List<String>)dTemplate.queryForList(namesSQL,  new Object[]{Integer.parseInt(values[POS_ID])}, String.class);
                for(String name :lnames){
                    addName(doc, name);
                }

                //determine whether or not the record represents an australian source
                //for now this will be determined using the lsid prefix in the future we may need to move to a more sophisticated method
                if(lsid.startsWith("urn:lsid:biodiversity.org.au"))
                    doc.setBoost(2.0f);
               
                writer.addDocument(doc);
                records++;
                if (records % 10000 == 0) {
                log.info("Processed " + records + " in " + (System.currentTimeMillis() - time) + " msecs (Total unprocessed: "+unprocessed+")");

            }
            }
            else{
                //can't process line without all values

                unprocessed++;
            }
         
        }
        writer.commit();
        writer.optimize();
        writer.close();
        log.info("Processed " + records + " in " + (System.currentTimeMillis() - time) + " msecs (Total unprocessed: "+unprocessed+")");


    }
//    public void updateHomonyms(String homonymFile, String indexDir) throws Exception {
//         //initalise the homonym list
//        LineIterator lines = new LineIterator(new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResource("org/ala/propertystore/known_homonyms.txt").openStream())));
//       // Set<String> knownHomonyms = new HashSet<String>();
//        IndexReader reader = IndexReader.open(FSDirectory.open(new File(indexDir)), false);
//        IndexSearcher searcher = new IndexSearcher(reader);
//        while (lines.hasNext()) {
//            String line = lines.nextLine().trim();
//            Term term = new Term(IndexField.GENUS.toString(), line);
//            Query query = new TermQuery(term);
//            TopDocs results = searcher.search(query, 2000000);
//            for(ScoreDoc sdoc : results.scoreDocs){
//                Document doc = reader.document(sdoc.doc);
//                doc.add(new Field(IndexField.HOMONYM.toString(), "T", Store.YES, Index.NOT_ANALYZED));
//                reader.deleteDocument(sdoc.doc);
//                //reader.
//            }
//            //knownHomonyms.add(line.toUpperCase());
//        }
//
//    }

    private void addName(Document doc, String name){
        doc.add(new Field(IndexField.NAMES.toString(), name, Store.NO, Index.ANALYZED));
        ParsedName cn = parser.parseIgnoreAuthors(name);
        //add the canonical form too (this uses a more generous name parser than the one that assigns the canonical form during the import process)
        if(cn != null){
            String canName = cn.buildCanonicalName();
            doc.add(new Field(IndexField.NAMES.toString(), canName, Store.NO, Index.ANALYZED));
        //TODO should the alternate names add to the searchable canonical field?
            //doc.add(new Field(IndexField.SEARCHABLE_NAME.toString(),tnse.soundEx(name), Store.NO, Index.ANALYZED));

        }
    }


    /**
     * Builds and returns the initial document
     * @param key
     * @param value
     * @param id
     * @param rank
     * @param rankString
     * @return
     */
     private Document buildDocument(String name, String classification, String id, String lsid, String rank, String rankString, String kingdom, String phylum, String genus) {
//        System.out.println("creating index " + name + " " + classification + " " + id + " " + lsid + " " + rank + " " + rankString+ " " + kingdom + " " + genus);
        Document doc = new Document();
        doc.add(new Field(IndexField.NAME.toString(), name, Store.NO, Index.ANALYZED));
        doc.add(new Field(IndexField.ID.toString(), id, Store.YES, Index.NOT_ANALYZED));
        doc.add(new Field(IndexField.RANK.toString(), rank, Store.YES, Index.NOT_ANALYZED));
        doc.add(new Field(IndexField.RANK.toString(), rankString, Store.YES, Index.NOT_ANALYZED));
        doc.add(new Field(IndexField.CLASS.toString(), classification, Store.YES, Index.NO));
        if(StringUtils.trimToNull(kingdom) != null)
            doc.add(new Field(IndexField.KINGDOM.toString(), kingdom, Store.YES, Index.ANALYZED));
        doc.add(new Field(IndexField.LSID.toString(), lsid, Store.YES, Index.NO));
        if(StringUtils.trimToNull(phylum) != null)
            doc.add(new Field(IndexField.PHYLUM.toString(), phylum, Store.YES, Index.ANALYZED));
        if(StringUtils.trimToNull(genus) != null){
            doc.add(new Field(IndexField.GENUS.toString(), genus, Store.NO, Index.ANALYZED));
            if(knownHomonyms.contains(genus.toUpperCase()))
                doc.add(new Field(IndexField.HOMONYM.toString(), "T", Store.YES, Index.NOT_ANALYZED));
        }
        //add a search_canonical for the record
        doc.add(new Field(IndexField.SEARCHABLE_NAME.toString(), tnse.soundEx(name), Store.NO, Index.NOT_ANALYZED));
        return doc;
    }

    public static void main(String[] args)throws Exception{
        CBCreateLuceneIndex indexer = new CBCreateLuceneIndex();
        indexer.init();
        if(args.length == 2){
            indexer.createIndex(args[0], args[1]);
        }
        else{
            System.out.println("org.ala.checklist.lucene.CBCreateLuceneIndex <cb-names export file> <index directory>");
            System.out.println("Attempting to use default values: /data/exports/cb_names.txt /data/lucene/cb/classification");
            indexer.createIndex("/data/exports/cb_names.txt", "/data/lucene/cb/classification");
        }
    }

}
