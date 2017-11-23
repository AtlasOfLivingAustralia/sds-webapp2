/*
 * Copyright (C) 2014 Atlas of Living Australia
 * All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 */
package au.org.ala.sds

import au.org.ala.names.search.ALANameSearcher
import au.org.ala.sds.model.Message
import au.org.ala.sds.model.SensitiveTaxon
import au.org.ala.sds.model.SensitivityInstance
import au.org.ala.sds.util.SensitiveSpeciesXmlBuilder;
import au.org.ala.sds.util.Configuration;
import au.org.ala.sds.validation.FactCollection
import au.org.ala.sds.validation.ServiceFactory
import au.org.ala.sds.validation.ValidationOutcome
import au.org.ala.sds.validation.ValidationReport
import au.org.ala.sds.validation.ValidationService;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.time.DateFormatUtils;

/**
 * Provides services for the SDS. Allows the sensitive species list to be automatically constructed from the
 * list tool.
 *
 * Automatically check for updates to lists. When changes occur the XML is regenerated.
 *
 * @author Natasha Quimby (natasha.quimby@csiro.au)
 */
class SDSService {

    /** The last time that the XML file for the sensitive species was generated */
    private Date lastUpdated;
    /** The file name for the SDS list */
    private String sensitiveFileName = "/data/sds/sensitive-species.xml";
    // lock used to ensure that only one refresh at a time will be performed
    private Object lock = new Object();

    private SensitiveSpeciesFinder finder;
    private ALANameSearcher searcher;
    private boolean firstLoad = true;
    private String status = "Initialising"
    def grailsApplication

    public SDSService(){
        //sensitiveFileName = grailsApplication.config.sds.file?:sensitiveFileName
        new Thread(){
            public void run(){
                try {
                    File file = new File(sensitiveFileName)
                    searcher = new ALANameSearcher(Configuration.getInstance().getNameMatchingIndex())
                    if (file.exists()) {
                        status = "Loading initial sensitive species list"
                        lastUpdated = new Date(file.lastModified())
                        log.info("Sensitive Species List last generated " + lastUpdated)
                        finder = SensitiveSpeciesFinderFactory.getSensitiveSpeciesFinder(file.toURI().toString(), searcher)
                        log.info("Finished loading the finder ")
                    } else {
                        status = "Generating sensitive species list"
                        forceReload()
                    }
                    firstLoad = false;
                    status = "Ready"
                } catch (Exception ex) {
                    status = "The sensitive species list has not loaded. Returning errors until fixed. Cause: " + ex.getMessage()
                    log.error("A-WOOGA! A-WOOGA! The sensitive species list has not loaded.", ex)
                }
            }
            }.start()
    }
    /**
     *
     * @return The last time the sds species list was constructed
     */
    def getLastUpdated(){
        return lastUpdated
    }

    /**
     * Check to see whether we are ready to go
     *
     * @return True if the lookup service can be used
     */
    def isReady() {
        return !firstLoad && finder != null
    }

    /**
     * Get the status string.
     * <p>
     * This provides information to users so that they can determine what the problem is.
     *
     * @return The status string
     */
    String getStatus() {
        return status
    }

    /**
     * Force a reload of the SDS list ignoring the last updated date.
     */
    public void forceReload(){
        reload(true);
    }

    /**
     * Reloads the SDS list from the species list tool.
     *
     * @param forced when true a refresh does NOT take into account the last updated date.
     */
    public void reload(boolean forced){
        synchronized (lock){
            String oldStatus = status
            status = "Reloading status lists"
            log.info("Testing to see if we need to update lists.")
            //when it is a forced reload the date is ignored.
            Date date = forced ? null : lastUpdated;
            File tmpFile = new File(sensitiveFileName +".tmp")
            try{
                FileOutputStream outputStream = new FileOutputStream(tmpFile);
                if(SensitiveSpeciesXmlBuilder.generateFromWebservices(outputStream, date)){
                    log.info("Reloaded the SDS lists to be used by the service and served up...")
                    outputStream.flush()
                    outputStream.close()
                    //rename the old if it exists
                    def currentFile = new File(sensitiveFileName)
                    if(currentFile.exists()) {
                        FileUtils.copyFile(currentFile, new File(sensitiveFileName+"."+ DateFormatUtils.format(lastUpdated, "yyyy-MM-dd_HHmm")))
                    }
                    FileUtils.copyFile(new File(sensitiveFileName + ".tmp"), new File(sensitiveFileName ))
                    lastUpdated = new Date()
                    initFinder()
                    status = "Ready"
                } else{
                    log.info("List is already up to date.")
                }
            } catch(FileNotFoundException e){
                status = oldStatus
                log.error("Unable to update the SDS species file." , e)
            } catch(IOException e){
                status = oldStatus
                log.error(e);
            } catch (Exception e){
                status = oldStatus
                log.error(e);
            }

        }
    }

    /**
     * reinitialises the finder after the species list has been reloaded. This is performed as soon as the list is reloaed
     * to counteract the lag in the species matching.
     */
    private void initFinder(){
        try{
            SensitiveSpeciesFinder tmpfinder = SensitiveSpeciesFinderFactory.getSensitiveSpeciesFinder("file:///data/sds/sensitive-species.xml", searcher, true);
            synchronized (lock){
                finder = tmpfinder
                firstLoad = false
            }
        }
        catch (Exception e){
            throw new IllegalStateException("Unable to recreate the SDS finder with new list.", e)
        }
    }


    public SpeciesReport lookupSpecies(String name, String latitude, String longitude, String date) {
        if(firstLoad || finder == null) { // Should not be called bu controller
            throw new IllegalStateException("The SDS finder has not been initialised, please try again in 5 minutes. Let us know if you continue to see this error message")
        }
        def status=[]
        def results=[:]
        SensitiveTaxon st = finder.findSensitiveSpecies(name)
        List<SensitivityInstance> instances = null
        if(!st) {
            status << "Species $name not found in list of sensitive species"
        } else if(st.isDateRequired() && !date) {
            instances = st.getInstances()
            status << "Date of occurrence is required to determine sensitivity of this species"
        }
        else{
            instances = st.getInstances()
            //validate the species with supplied data
            def facts = [:]
            facts[(FactCollection.DECIMAL_LATITUDE_KEY)]= latitude
            facts[(FactCollection.DECIMAL_LONGITUDE_KEY)] = longitude
            if(date){
                facts[(FactCollection.EVENT_DATE_KEY)] =date
            }
            log.debug("Creating validation service for " + st.getTaxonName())
            ValidationService service = ServiceFactory.createValidationService(st)

            log.debug("Validating " + st.getTaxonName());
            ValidationOutcome outcome = service.validate(facts);
            if (!outcome.isValid()) {
                ValidationReport report = outcome.getReport();
                for (Message msg : report.getMessages()) {
                    Message.Type type = msg.getType();
                    String typeStr = "";
                    if (!type.equals(Message.Type.INFO)) {
                        typeStr = msg.getType().toString() + " - ";
                    }
                    status << typeStr + msg.getMessageText()
                }
            } else{
                if(st.isConservation()){
                    if(outcome.isSensitive()){
                        String generalisation = (String) outcome.getResult().get("dataGeneralizations")
                        String decLat = (String) outcome.getResult().get("decimalLatitude")
                        String decLong = (String) outcome.getResult().get("decimalLongitude")
                        status << generalisation.substring(0, generalisation.indexOf('\n')) + "(" + decLat + "," + decLong + ")"
                    } else{
                        status << "Occurrence is not sensitive"
                    }
                }  else if (st.isPlantPest()) {
                    for (Message message : outcome.getReport().getMessages()) {
                        status << message.toString()
                    }
                }
            }
        }
        //populate the result
        def speciesReport = new SpeciesReport()
        speciesReport.scientificName = name
        if(st?.getCommonName()) {
            speciesReport.commonName = st.getCommonName()
        }
        if(st?.getAcceptedName() && st.getAcceptedName() != name) {
            speciesReport.acceptedName = st.getAcceptedName()
        }
        if(status.size()>0) {
            speciesReport.status = status
        }
        if(instances) {
            speciesReport.instances = instances
        }
        return speciesReport

    }
}
