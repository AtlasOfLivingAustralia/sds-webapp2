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

import au.org.ala.sds.util.Configuration
import au.org.ala.sds.util.GeoLocationHelper
import grails.converters.JSON

/**
 * The Controller to serve up the SDS webapp content.
 *
 * @author Natasha Quimby (natasha.quimby@csiro.au)
 */
class SDSController {
    def SDSService
    /*
        Renders the SDS front page
     */
    def index() {
        //return the date last generated for the service.
        render(model: [updateDate:SDSService.getLastUpdated()], view:'index')
    }
    /*
        Common web service to use to server up the XML files for the SDS
     */
    def getFile={
        if (params.file){
            //we have a value for the file
            log.debug("Returning " + params.file)
            //set the required response header
            response.setHeader("Cache-Control", "must-revalidate")

            String fileContents = new File('/data/sds/'+params.file.replaceAll("-data","")).getText('UTF-8')
            render(contentType: "text/xml", text:fileContents)
        }
    }
    /*
        Forces the sensitive species data list to be regenerated
     */
    def forceReload={
        def start=System.currentTimeMillis()
        SDSService.forceReload()
        def end = System.currentTimeMillis()
        render "Reload Complete in " + ((end-start).floatValue()/1000) + " seconds"
    }

    /**
     * WS to perform species lookup
     */
    def lookup ={
        log.debug(params)
        if (!SDSService.isReady())
            render(status: 503, text: "The SDS service is not yet ready. " + SDSService.status)
        else
            render SDSService.lookupSpecies(params.scientificName, params.latitude, params.longitude, params.date) as JSON
    }

    /**
     * SDS layers
     */
    def layers = {
        render Configuration.getInstance().getGeospatialLayers() as JSON
    }
}
