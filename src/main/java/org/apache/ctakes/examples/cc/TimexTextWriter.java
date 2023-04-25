package org.apache.ctakes.examples.cc;

import org.apache.ctakes.core.cc.AbstractJCasFileWriter;
import org.apache.ctakes.core.util.Pair;
import org.apache.ctakes.typesystem.type.textsem.TimeMention;
import org.apache.ctakes.typesystem.type.textspan.Sentence;
import org.apache.log4j.Logger;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;

import org.clulab.timenorm.scfg.*;
import scala.util.Success;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Note level event annotations for debugging chemo dictionary lookup
 *
 * @author Eli , chip-nlp
 * @version %I%
 * @since 4/6/2023
 */
final public class TimexTextWriter extends AbstractJCasFileWriter {


    static private final Logger LOGGER = Logger.getLogger( "EventTextWriter" );
    static private final String FILE_EXTENSION = ".txt";
    private static final TemporalExpressionParser normalizer = TemporalExpressionParser.en();
    // for now give a 'non time'
    static private final TimeSpan dummyDCT = TimeSpan.of(-999999999, 4, 1);

    @Override
    public void writeFile( final JCas jCas,
                           final String outputDir,
                           final String documentId,
                           final String fileName ) throws IOException {
        final File outputFilePath = new File( outputDir , fileName + "_time_mentions" + FILE_EXTENSION );
        LOGGER.info("Writing " + fileName + FILE_EXTENSION + " to " + outputFilePath.getPath()  +" ...") ;
        try ( Writer writer = new BufferedWriter( new FileWriter( outputFilePath ) ) ) {
            Map<Sentence, Collection<TimeMention>> sent2ColEvents = JCasUtil.indexCovered(
                    jCas,
                    Sentence.class,
                    TimeMention.class
            );

            Map<Sentence, List<TimeMention>> sent2Events = new HashMap<>();
            sent2ColEvents.forEach(
                    (sentence, timexes) -> sent2Events.put(
                            sentence,
                            timexes.stream()
                                    .sorted(
                                            Comparator.comparingInt(
                                                    TimeMention::getBegin
                                            )
                                    ).collect(Collectors.toList())
                    )
            );
            List<Sentence> casSentences = JCasUtil.select(jCas, Sentence.class)
                    .stream()
                    .sorted(
                            Comparator.comparingInt(
                                    Sentence :: getBegin
                            )
                    ).collect(Collectors.toList());

            casSentences
                    .stream()
                    .filter(
                            sent2Events::containsKey
                    )
                    .forEach(
                            sentence -> {
                                try {
                                    writeMention(
                                            casSentences.indexOf(sentence) + 1,
                                            sentence,
                                            sent2Events.get(sentence),
                                            writer
                                    );
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                ;
                            }
                    );
        }
        LOGGER.info( "Finished Writing" );
    }


    /**
     * Write a sentence from the document text
     *
     * @param container sentence containing the annotation
     * @param TimeMentions annotations
     * @param writer   writer to which pretty text for the sentence should be written
     * @throws IOException if the writer has issues
     */
    static public void writeMention(
            final int sentIndex,
            final Sentence container,
            final List<TimeMention> TimeMentions,
            final Writer writer
    ) throws IOException {

        writer.write(
                String.format(
                        "\nSentence: %d\n\n",
                        sentIndex
                )
        );

        writer.write(
                taggedSentence(
                        container,
                        TimeMentions
                )
        );
        writer.write("\n");
    }

    static private String taggedSentence(Sentence sentence, List<TimeMention> timeMentions){
        StringBuilder out = new StringBuilder();
        String tag = "timex";


        String sentenceText = sentence.getCoveredText().replace("\n", " ");
        int sentenceBegin = sentence.getBegin();
        int previous = 0;

        for (TimeMention timeMention : timeMentions){
            int localBegin = timeMention.getBegin() - sentenceBegin;
            int localEnd = timeMention.getEnd() - sentenceBegin;
            if (previous < localBegin) {
                out.append(sentenceText, previous, localBegin);
            }

            String unnormalized = timeMention.getCoveredText();

            Temporal normalized = normalizer.parse(unnormalized, dummyDCT).getOrElse(null);

            if (!(normalized == null)) {
                out.append(String.format("<%s>", tag));
                out.append(normalized);
                out.append(String.format("</%s>", tag));
            }
            previous = localEnd;
        }

        out.append(sentenceText, previous, sentenceText.length());

        return out.toString();
    }
}
