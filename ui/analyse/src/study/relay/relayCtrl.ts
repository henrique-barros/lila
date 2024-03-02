import { RelayData, LogEvent, RelaySync, RelayRound, RoundId } from './interfaces';
import {
  ChapterId,
  ChapterPreview,
  ServerClockMsg,
  ServerNodeMsg,
  StudyChapter,
  StudyChapterRelay,
} from '../interfaces';
import { isFinished } from '../studyChapters';
import { StudyMemberCtrl } from '../studyMembers';
import { AnalyseSocketSend } from '../../socket';
import { Prop, Toggle, prop, toggle } from 'common';
import RelayTeams from './relayTeams';
import { Redraw } from 'common/snabbdom';
import { fenColor } from 'common/miniBoard';
import { opposite } from 'chessops/util';

export const relayTabs = ['overview', 'boards', 'teams', 'leaderboard'] as const;
export type RelayTab = (typeof relayTabs)[number];

export default class RelayCtrl {
  log: LogEvent[] = [];
  cooldown = false;
  clockInterval?: number;
  tourShow: Toggle;
  tab: Prop<RelayTab>;
  teams?: RelayTeams;

  constructor(
    readonly id: RoundId,
    public data: RelayData,
    readonly send: AnalyseSocketSend,
    readonly redraw: Redraw,
    readonly members: StudyMemberCtrl,
    chapter: StudyChapter,
    private readonly chapters: Prop<ChapterPreview[]>,
    looksNew: boolean,
    setChapter: (id: ChapterId) => void,
  ) {
    this.applyChapterRelay(chapter, chapter.relay);
    this.tourShow = toggle((location.pathname.match(/\//g) || []).length < 5);
    const locationTab = location.hash.replace(/^#/, '') as RelayTab;
    const initialTab = relayTabs.includes(locationTab) ? locationTab : looksNew ? 'overview' : 'boards';
    this.tab = prop<RelayTab>(initialTab);
    this.teams = data.tour.teamTable
      ? new RelayTeams(
          id,
          setChapter,
          () => this.roundPath(),
          redraw,
          send,
          () => chapter.setup.variant.key,
        )
      : undefined;
  }

  openTab = (t: RelayTab) => {
    this.tab(t);
    this.tourShow(true);
    this.redraw();
  };

  setSync = (v: boolean) => {
    this.send('relaySync', v);
    this.redraw();
  };

  loading = () => !this.cooldown && this.data.sync?.ongoing;

  // only modifies the chapter
  applyChapterRelay = (c: StudyChapter, r?: StudyChapterRelay) => {
    if (this.clockInterval) clearInterval(this.clockInterval);
    if (r) {
      if (typeof r.secondsSinceLastMove !== 'undefined' && !r.lastMoveAt)
        r.lastMoveAt = Date.now() - r.secondsSinceLastMove * 1000;
      c.relay = r;
      if (!isFinished(c)) this.clockInterval = setInterval(this.redraw, 1000);
    }
  };
  private findChapterPreview = (id: ChapterId) => this.chapters().find(cp => cp.id == id);
  addNodeToChapterPreview = (msg: ServerNodeMsg) => {
    const cp = this.findChapterPreview(msg.p.chapterId),
      node = msg.n;
    if (!cp) console.warn(`ChapterPreview not found for addNode ${msg}`);
    else {
      cp.fen = node.fen;
      cp.lastMove = node.uci;
      const playerWhoMoved = cp.players && cp.players[opposite(fenColor(cp.fen))];
      playerWhoMoved && (playerWhoMoved.clock = node.clock);
      // at this point `(cp: ChapterPreview).lastMoveAt` becomes outdated but should be ok since not in use anymore
      // to mitigate bad usage, setting it as `undefined`
      cp.lastMoveAt = undefined;
      // TODO ugh? request all? what about broadcasts with 128 boards?
      // this.multiCloudEval.sendRequest();
      this.redraw();
    }
  };
  setClockToChapterPreview = (msg: ServerClockMsg) => {
    const cp = this.findChapterPreview(msg.p.chapterId);
    if (cp && cp.players) cp.players[opposite(fenColor(cp.fen))].clock = msg.c;
  };

  roundById = (id: string) => this.data.rounds.find(r => r.id == id);
  currentRound = () => this.roundById(this.id)!;

  fullRoundName = () => `${this.data.tour.name} - ${this.currentRound().name}`;

  tourPath = () => `/broadcast/${this.data.tour.slug}/${this.data.tour.id}`;
  roundPath = (round?: RelayRound) => {
    const r = round || this.currentRound();
    return r && `/broadcast/${this.data.tour.slug}/${r.slug}/${r.id}`;
  };

  updateAddressBar = (tourUrl: string, roundUrl: string) => {
    const url = this.tourShow() ? `${tourUrl}${this.tab() === 'overview' ? '' : `#${this.tab()}`}` : roundUrl;
    // when jumping from a tour tab to another page, remember which tour tab we were on.
    if (!this.tourShow() && location.href.includes('#')) history.pushState({}, '', url);
    else history.replaceState({}, '', url);
  };

  private socketHandlers = {
    relayData: (d: RelayData) => {
      if (d.sync) d.sync.log = this.data.sync?.log || [];
      this.data = d;
      this.redraw();
    },
    relaySync: (sync: RelaySync) => {
      this.data.sync = {
        ...sync,
        log: this.data.sync?.log || sync.log,
      };
      this.redraw();
    },
    relayLog: (event: LogEvent) => {
      if (!this.data.sync) return;
      this.data.sync.log.push(event);
      this.data.sync.log = this.data.sync.log.slice(-20);
      this.cooldown = true;
      setTimeout(() => {
        this.cooldown = false;
        this.redraw();
      }, 4500);
      this.redraw();
      if (event.error) {
        if (this.data.sync.log.slice(-2).every(e => e.error)) site.sound.play('error');
        console.warn(`relay synchronisation error: ${event.error}`);
      }
    },
  };

  socketHandler = (t: string, d: any): boolean => {
    const handler = (this.socketHandlers as SocketHandlers)[t];
    if (handler && d.id === this.id) {
      handler(d);
      return true;
    }
    return false;
  };
}
