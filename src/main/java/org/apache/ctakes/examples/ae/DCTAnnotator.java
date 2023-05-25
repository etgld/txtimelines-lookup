package org.apache.ctakes.examples.ae;

import org.apache.commons.io.FilenameUtils;
import org.apache.ctakes.core.pipeline.PipeBitInfo;
import org.apache.ctakes.core.util.doc.SourceMetadataUtil;
import org.apache.ctakes.temporal.ae.DocTimeApproximator;
import org.apache.ctakes.typesystem.type.structured.SourceData;
import org.apache.log4j.Logger;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.cleartk.util.ViewUriUtil;

import java.util.Arrays;
import java.util.Optional;

@PipeBitInfo(
        name = "DCTAnnotator ( TxTimelines )",
        description = " Gets the DCT either from the note filename ( if the filename conforms to spec ) or from the header if it's a UPMC note" //,
        //dependencies = { PipeBitInfo.TypeProduct.EVENT },
        //products = { PipeBitInfo.TypeProduct.DOC }
)

public class DCTAnnotator extends org.apache.uima.fit.component.JCasAnnotator_ImplBase {
    final static private Logger LOGGER = Logger.getLogger( "DCTAnnotator" );
    final static private DocTimeApproximator _approximator = new DocTimeApproximator();
    @Override
    public void initialize( UimaContext context ) throws ResourceInitializationException {
        super.initialize( context );
    }


    @Override
    public void process( JCas jCas ) throws AnalysisEngineProcessException {
        final SourceData sourceData = SourceMetadataUtil.getOrCreateSourceData( jCas );

        String fileName = FilenameUtils.getBaseName( ViewUriUtil.getURI( jCas ).getPath() );
        String[] fileNameElements = fileName.split( "_" );
        if ( fileNameElements.length >= 3 ){
            String[] possibleDate = fileNameElements[ 2 ].split( "-" );
            if ( possibleDate.length == 3 ){

                int month = Integer.parseInt( possibleDate[ 0 ] );
                int day = Integer.parseInt( possibleDate[ 1 ] );
                int year = Integer.parseInt( possibleDate[ 2 ] );

                String docTime = year + "-" + String.format( "%02d", month ) + "-" + String.format( "%02d", day );

                sourceData.setSourceOriginalDate( docTime );
                LOGGER.info( "DeepPhe Spec Note" + fileName + " Time is Set to " + docTime );
            }
            LOGGER.info( "DeepPhe Non-Spec Note" + fileName + ": Ensure is UPMC" );
        }

        // Otherwise this is a UPMC note
        Optional<String> principalDateLine = Arrays.stream(jCas.getDocumentText().split("\n"))
                .filter( s -> s.startsWith( "Principal Date" ) )
                .findFirst();

        String rawDCT = principalDateLine
                .map(
                        s -> s.replaceAll(
                                "[^\\d]]",
                                ""
                        )
                )
                .orElse("UNK");

        if ( rawDCT.equals( "UNK" ) ){
            LOGGER.info( "DeepPhe Non-Spec Note" + fileName + ": Could Not Find Principal Date In Header! Resorting to DocTimeApproximator" );
            _approximator.process( jCas );
            return;
        }

        assert rawDCT.length() == 8; // 20<YY><MM><DD>
        String year = rawDCT.substring( 0, 3 );
        String month = rawDCT.substring( 4, 5 );
        String date = rawDCT.substring( 6, 7 );

        String docTime = year + "-" + month + "-" + date;
        sourceData.setSourceOriginalDate( docTime );
        LOGGER.info( "DeepPhe Non-Spec Note" + fileName + " Time is Set to " + docTime );
    }
}