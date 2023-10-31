import asyncio
import time

import tlink_rest
import dtr_rest
import conmod_rest
from ctakes_pbj.component import cas_annotator
from ctakes_pbj.pbj_tools import create_type
from ctakes_pbj.pbj_tools.create_relation import create_relation
from ctakes_pbj.pbj_tools.event_creator import EventCreator
from ctakes_pbj.pbj_tools.get_common_types import get_or_create_event_mention
from ctakes_pbj.pbj_tools.helper_functions import *
from ctakes_pbj.pbj_tools.token_tools import *
from ctakes_pbj.type_system import ctakes_types

sem = asyncio.Semaphore(1)


class TimelineDelegator(cas_annotator.CasAnnotator):

    def __init__(self, cas):
        self.event_mention_type = cas.typesystem.get_type(ctakes_types.EventMention)
        self.timex_type = cas.typesystem.get_type(ctakes_types.TimeMention)
        self.tlink_type = cas.typesystem.get_type(ctakes_types.TemporalTextRelation)
        self.argument_type = cas.typesystem.get_type(ctakes_types.RelationArgument)
        self.dtr_path = None
        self.tlink_path = None
        self.conmod_path = None

    def init_params(self, args):
        self.dtr_path = args.dtr_path
        self.tlink_path = args.tlink_path
        self.conmod_path = args.conmod_path

    # Initializes cNLPT, which loads its Temporal model.
    def initialize(self):
        print(time.ctime((time.time())), "Initializing cnlp-transformers temporal ...")
        asyncio.run(self.init_caller())
        print(time.ctime((time.time())), "Done.")

    def declare_params(self, arg_parser):
        arg_parser.add_arg("dtr_path")
        arg_parser.add_arg("tlink_path")
        arg_parser.add_arg("conmod_path")

    # Process Sentences, adding Times, Events and TLinks found by cNLPT.
    def process(self, cas):
        print(time.ctime((time.time())), "Processing cnlp-transformers timelines ...")
        sentences = cas.select(ctakes_types.Sentence)
        event_mentions = cas.select(ctakes_types.EventMention)

        def is_positive(event_mention):
            return event_mention.Event.properties.polarity == 1

        def is_actual(event_mention):
            return event_mention.Event.properties.contextualModality == "ACTUAL"

        positive_sentence_events = [[*filter(is_positive, sent_events)] for sent_events in get_covered_list(sentences, event_mentions)]

        tokens = cas.select(ctakes_types.BaseToken)
        sentence_tokens = get_covered_list(sentences, tokens)

        i = 0
        while i < len(sentences):
            if len(positive_sentence_events[i]) > 0:
                print(time.ctime((time.time())), "Processing cnlp-transformers timelines on sentence",
                      str(i), "of", str(len(sentences)), "...")
                event_offsets = get_windowed_offsets(positive_sentence_events[i], sentences[i].begin)
                token_offsets = get_windowed_offsets(sentence_tokens[i], sentences[i].begin)
                asyncio.run(self.temporal_caller(cas, sentences[i], sentence_events[i], token_offsets))
            i += 1

        for index, packet in enumerate(zip(sentences, positive_sentence_events)):
            sentence, events = packet
            if len(events) > 0:
                pass

        print(time.ctime((time.time())), "cnlp-transformers temporal Done.")

    async def init_caller(self):
        await tlink_rest.startup_event(
            tlink_path=self.tlink_path,
        )
        await dtr_rest.startup_event(
            dtr_path=self.dtr_path
        )
        await conmod_rest.startup_event(
            conmod_path=self.conmod_path
        )

    async def temporal_caller(self, cas, sentence, event_mentions, token_offsets):

        sentence_doc = tlink_rest.SentenceDocument(sentence.get_covered_text())
        temporal_result = await tlink_rest.process_sentence(sentence_doc)

        events_times = {}
        i = 0
        for t in temporal_result.timexes:
            for tt in t:
                first_token_offset = token_offsets[tt.begin]
                last_token_offset = token_offsets[tt.end]
                timex = create_type.add_type(cas, self.timex_type,
                                             sentence.begin + first_token_offset[0],
                                             sentence.begin + last_token_offset[1])
                events_times['TIMEX-' + str(i)] = timex
                i += 1

        i = 0
        for e in temporal_result.events:
            for ee in e:
                first_token_offset = token_offsets[ee.begin]
                last_token_offset = token_offsets[ee.end]
                event_mention = get_or_create_event_mention(cas, event_mentions,
                                                            sentence.begin + first_token_offset[0],
                                                            sentence.begin + last_token_offset[1])
                event = EventCreator.create_event(cas, ee.dtr)
                event_mention.event = event
                events_times['EVENT-' + str(i)] = event_mention
                i += 1

        for r in temporal_result.relations:
            for rr in r:
                arg1 = self.argument_type()
                arg1.argument = events_times[rr.arg1]
                print("Arg1 =", events_times[rr.arg1])
                arg2 = self.argument_type()
                arg2.argument = events_times[rr.arg2]
                print("Arg2 =", events_times[rr.arg2])
                tlink = create_relation(self.tlink_type, rr.category, arg1, arg2)
                cas.add(tlink)

    def get_index_by_offsets(self, tokens, begin, end):
        i = 0
        for token in tokens:
            if token.begin == begin and token.end == end:
                return i
            i += 1
        return -1

    def get_or_create_event_mention(self, cas, event_mentions, begin, end):
        i = self.get_index_by_offsets(event_mentions, begin, end)
        if i == -1:
            return create_type.add_type(cas, self.event_mention_type, begin, end)
        return event_mentions[i]
