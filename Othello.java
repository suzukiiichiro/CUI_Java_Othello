import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
/**
 * 評価関数
 */
interface Evaluator {
	public int evaluate(Board board);
}
/**
 * 終盤の完全読み用評価関数 単に石数の差を評価値としています。 黒石の数から白石
 * の数を引いたものは黒にとっての評価値です。これにboard.getCurrentColor()を掛
 * ける事によって、 現在のboardの手番にとっての評価値が得られます。このように手
 * 番に応じた評価値を返すようにしているのは、 negamax法やNegaScoutで用いる事を
 * 前提としているからです。
 */
class PerfectEvaluator implements Evaluator {
	public int evaluate(Board board) {
		int discdiff = board.getCurrentColor() * (board.countDisc(Disc.BLACK) - board.countDisc(Disc.WHITE));
		return discdiff;
	}
}
/**
 * 終盤初期で必勝読みを行う際に用いる評価関数
 */
class WLDEvaluator implements Evaluator {
	public static final int WIN = 1;
	public static final int DRAW = 0;
	public static final int LOSE = -1;
	public int evaluate(Board board) {
		int discdiff = board.getCurrentColor() * (board.countDisc(Disc.BLACK) - board.countDisc(Disc.WHITE));
		if (discdiff > 0) {
			return WIN;
		} else if (discdiff < 0) {
			return LOSE;
		} else {
			return DRAW;
		}
	}
}
/**
 * 中盤の評価関数 ・着手可能手数のプラス評価 ・開放度のマイナス評価 ・ウイング
 * のマイナス評価 ・確定石の個数のプラス評価・危険なC打ち（隅がとられていない状
 * 態でのC打ち)のマイナス評価 ・危険なX打ち（隅が撮られていない状態でのX打ち)の
 * マイナス評価
 * 着手可能手数については、評価速度を上げるために、 現在の手番の色についてのみ
 * 数えて評価しています。
 * それぞれのパラメータごとの重み係数は InternetOthelloServerの対戦履歴を用いて
 * 最適化 これだけの評価関数＋５手先読み程度の思考ルーチンで１級程度
 */
class MidEvaluator implements Evaluator {
	/**
   * 「確定石の個数」「ウイングの個数」「危険なＣ打ちの個数」といった 変に関す
   * るパラメータをまとめたクラス。山についての変数も用意されているが、今回は山
   * の評価は行っていない。
	 */
	class EdgeParam {
		public EdgeParam add(EdgeParam src) {
			stable += src.stable;
			wing += src.wing;
			mountain += src.mountain;
			Cmove += src.Cmove;
			return this;
		}
		// 代入演算子の代わり
		public void set(EdgeParam e) {
			stable = e.stable;
			wing = e.wing;
			mountain = e.mountain;
			Cmove = e.Cmove;
		}
		public byte stable = 0; // 確定石の個数
		public byte wing = 0; // ウイングの個数
		public byte mountain = 0; // 山の個数
		public byte Cmove = 0; // 危険なC打ちの個数
	}
	/**
   * 「隅にある石の個数」と「危険なＸ打ちの個数」など、 隅周辺に関するパラメー
   * タを集めたクラス
	 */
	class CornerParam {
		public byte corner = 0; // 隅にある石の数
		public byte Xmove = 0; // 危険なX打ちの個数
	}
	/**
	 * 色別のEdgeParamオブジェクトを管理するクラス
	 */
	class EdgeStat {
		private EdgeParam[] data = new EdgeParam[3];
		public EdgeStat() {
			for (int i = 0; i < 3; i++)
				data[i] = new EdgeParam();
		}
		public void add(EdgeStat e) {
			for (int i = 0; i < 3; i++)
				data[i].add(e.data[i]);
		}
		public EdgeParam get(int color) {
			return data[color + 1];
		}
	}
	/**
	 * 色別のCornerParamオブジェクトを管理するクラス
	 */
	class CornerStat {
		private CornerParam[] data = new CornerParam[3];
		public CornerStat() {
			for (int i = 0; i < 3; i++)
				data[i] = new CornerParam();
		}
		public CornerParam get(int color) {
			return data[color + 1];
		}
	}
	/**
	 * 重み係数を規定する構造体
	 */
	class Weight {
		int mobility_w;
		int liberty_w;
		int stable_w;
		int wing_w;
		int Xmove_w;
		int Cmove_w;
	}
	private Weight EvalWeight;
	private static final int TABLE_SIZE = 6561; // 3^8
	private static EdgeStat[] EdgeTable = new EdgeStat[TABLE_SIZE];
	private static boolean TableInit = false;
	public MidEvaluator() {
		if (!TableInit) {
			// 初回起動時にテーブルを生成
			int[] line = new int[Board.BOARD_SIZE];
			generateEdge(line, 0);
			TableInit = true;
		}
		// 重み係数の設定 (全局面共通)
		EvalWeight = new Weight();
		EvalWeight.mobility_w = 67;
		EvalWeight.liberty_w = -13;
		EvalWeight.stable_w = 101;
		EvalWeight.wing_w = -308;
		EvalWeight.Xmove_w = -449;
		EvalWeight.Cmove_w = -552;
	}
	public int evaluate(Board board) {
		EdgeStat edgestat;
		CornerStat cornerstat;
		int result;
		// 辺の評価
		edgestat = EdgeTable[idxTop(board)];
		edgestat.add(EdgeTable[idxBottom(board)]);
		edgestat.add(EdgeTable[idxRight(board)]);
		edgestat.add(EdgeTable[idxLeft(board)]);
		// 隅の評価
		cornerstat = evalCorner(board);
		// 確定石に関して、隅の石を2回数えてしまっているので補正。
		edgestat.get(Disc.BLACK).stable -= cornerstat.get(Disc.BLACK).corner;
		edgestat.get(Disc.WHITE).stable -= cornerstat.get(Disc.WHITE).corner;
		// パラメータの線形結合
		result = edgestat.get(Disc.BLACK).stable * EvalWeight.stable_w
				- edgestat.get(Disc.WHITE).stable * EvalWeight.stable_w
				+ edgestat.get(Disc.BLACK).wing * EvalWeight.wing_w - edgestat.get(Disc.WHITE).wing * EvalWeight.wing_w
				+ cornerstat.get(Disc.BLACK).Xmove * EvalWeight.Xmove_w
				- cornerstat.get(Disc.WHITE).Xmove * EvalWeight.Xmove_w
				+ edgestat.get(Disc.BLACK).Cmove * EvalWeight.Cmove_w
				- edgestat.get(Disc.WHITE).Cmove * EvalWeight.Cmove_w;
		// 開放度・着手可能手数の評価
		if (EvalWeight.liberty_w != 0) {
			ColorStorage liberty = countLiberty(board);
			result += liberty.get(Disc.BLACK) * EvalWeight.liberty_w;
			result -= liberty.get(Disc.WHITE) * EvalWeight.liberty_w;
		}
		// 現在の手番の色についてのみ、着手可能手数を数える
		result += board.getCurrentColor() * board.getMovablePos().size() * EvalWeight.mobility_w;
		return board.getCurrentColor() * result;
	}
	private void generateEdge(int[] edge, int count) {
		if (count == Board.BOARD_SIZE) {
			// このパターンは完成したので、局面のカウント
			EdgeStat stat = new EdgeStat();
			stat.get(Disc.BLACK).set(evalEdge(edge, Disc.BLACK));
			stat.get(Disc.WHITE).set(evalEdge(edge, Disc.WHITE));
			EdgeTable[idxLine(edge)] = stat;
			return;
		}
		// 再帰的に全てのパターンを網羅
		edge[count] = Disc.EMPTY;
		generateEdge(edge, count + 1);
		edge[count] = Disc.BLACK;
		generateEdge(edge, count + 1);
		edge[count] = Disc.WHITE;
		generateEdge(edge, count + 1);
		return;
	}
	EdgeParam evalEdge(int line[], int color) {
		EdgeParam edgeparam = new EdgeParam();
		int x;
		// ウィング等のカウント
		if (line[0] == Disc.EMPTY && line[7] == Disc.EMPTY) {
			x = 2;
			while (x <= 5) {
				if (line[x] != color) {
					break;
				}
				x++;
			}
			// 少なくともブロックができている
			if (x == 6) {
				if (line[1] == color && line[6] == Disc.EMPTY) {
					edgeparam.wing = 1;
				} else if (line[1] == Disc.EMPTY && line[6] == color) {
					edgeparam.wing = 1;
				} else if (line[1] == color && line[6] == color) {
					edgeparam.mountain = 1;
				}
				// それ以外の場合に、隅に隣接する位置に置いていたら
			} else {
				if (line[1] == color) {
					edgeparam.Cmove++;
				}
				if (line[6] == color) {
					edgeparam.Cmove++;
				}
			}
		}
		/**
		 * 確定石のカウント
		 */
		// 左から右方向に走査
		for (x = 0; x < 8; x++) {
			if (line[x] != color)
				break;
			edgeparam.stable++;
		}
		if (edgeparam.stable < 8) {
			// 右側からの走査も必要
			for (x = 7; x > 0; x--) {
				if (line[x] != color)
					break;
				edgeparam.stable++;
			}
		}
		return edgeparam;
	}
	CornerStat evalCorner(Board board) {
		CornerStat cornerstat = new CornerStat();
		cornerstat.get(Disc.BLACK).corner = 0;
		cornerstat.get(Disc.BLACK).Xmove = 0;
		cornerstat.get(Disc.WHITE).corner = 0;
		cornerstat.get(Disc.WHITE).Xmove = 0;
		Point p = new Point();
		// 左上
		p.x = 1;
		p.y = 1;
		cornerstat.get(board.getColor(p)).corner++;
		if (board.getColor(p) == Disc.EMPTY) {
			p.x = 2;
			p.y = 2;
			cornerstat.get(board.getColor(p)).Xmove++;
		}
		// 左下
		p.x = 1;
		p.y = 8;
		cornerstat.get(board.getColor(p)).corner++;
		if (board.getColor(p) == Disc.EMPTY) {
			p.x = 2;
			p.y = 7;
			cornerstat.get(board.getColor(p)).Xmove++;
		}
		// 右下
		p.x = 8;
		p.y = 8;
		cornerstat.get(board.getColor(p)).corner++;
		if (board.getColor(p) == Disc.EMPTY) {
			p.x = 7;
			p.y = 7;
			cornerstat.get(board.getColor(p)).Xmove++;
		}
		// 右上
		p.x = 8;
		p.y = 1;
		cornerstat.get(board.getColor(p)).corner++;
		if (board.getColor(p) == Disc.EMPTY) {
			p.x = 7;
			p.y = 7;
			cornerstat.get(board.getColor(p)).Xmove++;
		}
		return cornerstat;
	}
	int idxTop(Board board) {
		int index = 0;
		int m = 1;
		Point p = new Point(0, 1);
		for (int i = Board.BOARD_SIZE; i > 0; i--) {
			p.x = i;
			index += m * (board.getColor(p) + 1);
			m *= 3;
		}
		return index;
	}
	int idxBottom(Board board) {
		int index = 0;
		int m = 1;
		Point p = new Point(0, 8);
		for (int i = Board.BOARD_SIZE; i > 0; i--) {
			p.x = i;
			index += m * (board.getColor(p) + 1);
			m *= 3;
		}
		return index;
	}
	int idxRight(Board board) {
		int index = 0;
		int m = 1;
		Point p = new Point(8, 0);
		for (int i = Board.BOARD_SIZE; i > 0; i--) {
			p.y = i;
			index += m * (board.getColor(p) + 1);
			m *= 3;
		}
		return index;
	}
	int idxLeft(Board board) {
		int index = 0;
		int m = 1;
		Point p = new Point(1, 0);
		for (int i = Board.BOARD_SIZE; i > 0; i--) {
			p.y = i;
			index += m * (board.getColor(p) + 1);
			m *= 3;
		}
		return index;
	}
	private ColorStorage countLiberty(Board board) {
		ColorStorage liberty = new ColorStorage();
		liberty.set(Disc.BLACK, 0);
		liberty.set(Disc.WHITE, 0);
		liberty.set(Disc.EMPTY, 0);
		Point p = new Point();
		for (int x = 1; x <= Board.BOARD_SIZE; x++) {
			p.x = x;
			for (int y = 1; y <= Board.BOARD_SIZE; y++) {
				p.y = y;
				int l = liberty.get(board.getColor(p));
				l += board.getLiberty(p);
				liberty.set(board.getColor(p), l);
			}
		}
		return liberty;
	}
	private int idxLine(int[] l) {
		return 3 * (3 * (3 * (3 * (3 * (3 * (3 * (l[0] + 1) + l[1] + 1) + l[2] + 1) + l[3] + 1) + l[4] + 1) + l[5] + 1)
				+ l[6] + 1) + l[7] + 1;
	}
}
/**
 * 探索アルゴリズムパラメータ
 *
 */
abstract class AI {
	abstract public void move(Board board);
	// alpha-beta法やNegaScout法において、事前に手を調べて
	// 探索順序を決めるための先読み手数
	public int presearch_depth = 3;
	// 序盤・中盤の探索における先読み手数
	public int normal_depth = 15;
	// 終盤において、必勝読みを始める残り手数。
	// WLDという名前はWin,lose,drawからきている
	public int wld_depth = 15;
	// 終盤において、完全読みを始める残り手数
	public int perfect_depth = 13;
}
/**
 * 探索アルゴリズム
 */
class AlphaBetaAI extends AI {
	class Move extends Point {
		public int eval = 0;
		public Move() {
			super(0, 0);
		}
		public Move(int x, int y, int e) {
			super(x, y);
			eval = e;
		}
	};
	private Evaluator Eval = null;// 評価関数
	/**
	 * sort() 選択ソート
	 * 事前に浅い先読みを行って評価値の高い順に手を並べ替えるためのメソッド
	 */
	private void sort(Board board, Vector movables, int limit) {
		Vector moves = new Vector();
		Point p = null;
		int eval ;
		for (int i = 0; i < movables.size(); i++) {
			p = (Point) movables.get(i);
			board.move(p);
			eval = -alphabeta(board, limit - 1, -Integer.MAX_VALUE, Integer.MAX_VALUE);
			board.undo();
			Move move = new Move(p.x, p.y, eval);
			moves.add(move);
		}
		// 評価値の大きい順にソート(選択ソート)
		for (int begin = 0; begin < moves.size() - 1; begin++) {
			for (int current = 1; current < moves.size(); current++) {
				Move b = (Move) moves.get(begin);
				Move c = (Move) moves.get(current);
				if (b.eval < c.eval) {
					// 交換
					moves.set(begin, c);
					moves.set(current, b);
				}
			}
		}
		// 結果の書き戻し
		movables.clear();
		for (int i = 0; i < moves.size(); i++) {
			movables.add(moves.get(i));
		}
		return;
	}
	/**
	 * miniMax
	 * @param board
	 * @param movables
	 */
	public void negamax(Board board, Vector movables){
		Point p = null; // 打つ手の候補
		int eval ; 
		int eval_max = Integer.MIN_VALUE;
		for (int i = 0; i < movables.size(); i++) { // 全ての手
			// 手を打つ
			board.move((Point) movables.get(i));
			//  alphabeta法 下のノードから上がってきた値の符号を反転させる 最大値と最小値を交互に取る事が出来る
			eval = -alphabeta(board, limit - 1, -Integer.MAX_VALUE, -Integer.MIN_VALUE);
			// 手を戻す
			board.undo(); 
			System.out.print( (Point)movables.get(i)+" " ) ;
			// よりよい手を見つける
			if (eval > eval_max) {
				p = (Point) movables.get(i); // 今打った手
			}
		}
		//最も良い手を打つ
		board.move(p); 
		System.out.println("[" + p.toString() + "]");
	}
	/**
	 * alphabeta() miniMax, αβ法
	 * miniMax 自分にとっての最善手とは、相手にとっても最悪手という考え方に基づいています。
	 * alphaBeta 絶対に採用される事がない手をそれ以上読まない事で枝刈りを行い効率を大幅に向上させます。
	 */
	private int alphabeta(Board board, int limit, int alpha, int beta) {
		if (board.isGameOver() || limit == 0) {
			// 深さ制限に達したら評価値を返す
			return evaluate(board);
		}
		Vector pos = board.getMovablePos();
		int eval;
		if (pos.size() == 0) {
			// パス
			board.pass();
			// negaMax 
			eval = -alphabeta(board, limit, -beta, -alpha);
			// 元に戻す
			board.undo();
			return eval;
		}
		for (int i = 0; i < pos.size(); i++) {
			//打つ
			board.move((Point) pos.get(i));
			// negaMax 次の相手の手 下のノードから上がってきた値の符号を反転させる 最大値と最小値を交互に撮る事が出来る
			eval = -alphabeta(board, limit - 1, -beta, -alpha);
			//元に戻す
			board.undo();
			// α値を更新
			alpha = Math.max(alpha, eval);
			// よりよい手（悪い手）が見つかった
			if (alpha >= beta) {
				return alpha; // β刈り
			}
		}
		return alpha;
	}
	/**
	 * negaScout法 (評価関数呼び出し）
	 * まず最善手となりそうな手を予想して探索し、 それ以外の手につ
	 * いては「より悪い手である」事のみを高速に確認（scout)することで効率を上げ
	 * る事を狙います。 手を良さそうな順に並べ替えた上で、最善手と思われる手に
	 * ついて通常のαβ探索を行いαを求めます。
	 * 
	 * ゲーム開始------------------------ゲーム終了 
	 * presearch_depth 
	 *            normal_depth 
	 *                    wld_depth
	 *                          prefect_depth
	 */
	// 深さ制限
	int limit=0;
	public void negaScout(Board board, Vector movables){
		// 序盤から中盤
		Eval = new MidEvaluator(); 
		// 事前にソート
		sort(board, movables, presearch_depth); 
		if (Board.MAX_TURNS - board.getTurns() <= wld_depth) {
			limit = Integer.MAX_VALUE; 
			// 可能な手を全て生成
			if (Board.MAX_TURNS - board.getTurns() <= perfect_depth) {
				// 終盤完全読み
				Eval = new PerfectEvaluator(); 
			} else {
				// 終盤必勝読み
				Eval = new WLDEvaluator(); 
			}
		} else {
			limit = normal_depth; // 評価値
		}
	}
	/**
	 * move()
	 */
	public void move(Board board) {
		BookManager book = new BookManager();
		Vector movables = book.find(board);
		if (movables.isEmpty()) { // 打てる箇所がない
			board.pass(); // パス
			return;
		}
		if (movables.size() == 1) { // 打てる箇所が一カ所
			board.move((Point) movables.get(0)); // 即座に打って返す
			System.out.println("[" + ((Point) movables.get(0)).toString() + "]");
			return;
		}
		// * negaScout
		negaScout(board, movables) ;
		//* negaMax( minimax + Alpha+Beta )
		negamax(board, movables) ;
	}
	// 仮
	public int evaluate(Board board) {
		return 0;
	}
}

/**
 * ＡＩプレイヤー
 */
class AIPlayer implements Player {
	private AI Ai = null;
	public AIPlayer() {
		Ai = new AlphaBetaAI();
	}
	public void onTurn(Board board) throws GameOverException {
		System.out.print("コンピュータが思考中...");
		Ai.move(board);
		System.out.println("完了");
		if (board.isGameOver()) {
			throw new GameOverException();
		}
	}
}
/**
 * ヒューマンプレイヤー
 */
class HumanPlayer implements Player {
	public void onTurn(Board board) throws Exception {
		if (board.getMovablePos().isEmpty()) {
			System.out.println("あなたはパスです。");
			board.pass();
			return;
		}
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			System.out.print("(ex.f5/U:取消/X:終了)");
			//打てる候補を出力 スコア順ではない
			System.out.println( "あなたの手番" + board.getMovablePos() ) ;
			String in = br.readLine();
			if (in.equalsIgnoreCase("U")) {
				throw new UndoException();
			}
			if (in.equalsIgnoreCase("X")) {
				throw new ExitException();
			}
			Point p;
			try {
				p = new Point(in);
			} catch (IllegalArgumentException e) {
				System.out.println("正しい形式で入力してください！");
				continue;
			}
			if (!board.move(p)) {
				System.out.println("そこには置けません！");
				continue;
			}
			if (board.isGameOver()) {
				throw new GameOverException();
			}
			break;
		}
	}
}
interface Player {
	public void onTurn(Board board) throws Exception;
}
class UndoException extends Exception {
}
class ExitException extends Exception {
}
class GameOverException extends Exception {
}
/**
 * 定石探索 定石データベースの保管と探索
 */
class BookManager {
	private static final String BOOK_FILE_NAME = "reversi.book";
	class Node {
		public Node child = null;
		public Node sibling = null;
		// public int eval = 0;
		public Point point = new Point();
	}
	private Node Root = null;
	public BookManager() {
		Root = new Node();
		Root.point = new Point("f5");
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(BOOK_FILE_NAME);
		} catch (FileNotFoundException e) {
			return;
		}
		BufferedReader br = new BufferedReader(new InputStreamReader(fis));
		String line;
		try {
			// 行頭が#で始まっていたらコメントアウト
			String regex = "^#";
			Pattern pattern = Pattern.compile(regex);
			Matcher m = null;
			while ((line = br.readLine()) != null) {
				m = pattern.matcher(line);
				if (m.find()) {
					// System.out.println("#"+ line);
				} else {
					Vector book = new Vector();
					for (int i = 0; i < line.length(); i += 2) {
						Point p = null;
						try {
							p = new Point(line.substring(i));
						} catch (IllegalArgumentException e) {
							break;
						}
						book.add(p);
						 //System.out.println(p.toString());
					}
					add(book);
					// System.out.println(book.toString());
				}
			}
		} catch (IOException e) {
		} catch (NullPointerException e) {
		}
	}
	/**
   * 現在のボードの状態から次に打つべき定石手を探す 打てる手を並べたvectorを返
   * す。 定石手があるならvectorにはその手のみが入り、定石手がないなら
   * board.getMovablePosと同じ結果が入る。
	 */
	public Vector find(Board board) {
		Node node = Root;
		Vector history = board.getHistory();
		if (history.isEmpty()) {
			return board.getMovablePos();
		}
		Point first = (Point) history.get(0);
		CoordinatesTransformer transformer = new CoordinatesTransformer(first);
		// 座標を変換してf5から始まるようにする
		Vector normalized = new Vector();
		for (int i = 0; i < history.size(); i++) {
			Point p = (Point) history.get(i);
			p = transformer.normalize(p);
			normalized.add(p);
		}
		// 現在までの棋譜リストと定石の対応を取る
		for (int i = 1; i < normalized.size(); i++) {
			Point p = (Point) normalized.get(i);
			node = node.child;
			while (node != null) {
				if (node.point.equals(p))
					break;
				node = node.sibling;
			}
			if (node == null) {
				// 定石を外れている
				return board.getMovablePos();
			}
		}
		// 履歴と定石の終わりが一致していた場合
		if (node.child == null) {
			return board.getMovablePos();
		}
		Point next_move = getNextMove(node);
		// 座標を元の形に変換する
		next_move = transformer.denormalize(next_move);
		Vector v = new Vector();
		v.add(next_move);
		return v;
	}
	/**
   * 次の一手を決める 引数nodeは、現在の手数の位置に対応するノード つまり、
   * node.childの世代が次の手を表すノード
	 */
	private Point getNextMove(Node node) {
		Vector candidates = new Vector();
		for (Node p = node.child; p != null; p = p.sibling) {
			candidates.add(p.point);
		}
		int index = (int) (Math.random() * candidates.size());
		Point point = (Point) candidates.get(index);
		return new Point(point.x, point.y);
	}
	/**
	 * bookで指定された定石を定石木に追加する
	 */
	private void add(Vector book) {
		Node node = Root;
		for (int i = 1; i < book.size(); i++) {
			Point p = (Point) book.get(i);
			if (node.child == null) {
				// 新しい定石手
				node.child = new Node();
				node = node.child;
				node.point.x = p.x;
				node.point.y = p.y;
			} else {
				// 兄弟ノードの探索に移る
				node = node.child;
				while (true) {
					// 既にこの手はデータベース中にあり、その枝を見つけた
					if (node.point.equals(p)){
						break;
					}
					// 定石木の新しい枝
					if (node.sibling == null) {
						node.sibling = new Node();
						node = node.sibling;
						node.point.x = p.x;
						node.point.y = p.y;
						break;
					}
					node = node.sibling;
				}
			}
		}
	}
}
/**
 * 座標の変換および逆変換を行う 定石は全てf5から始まる 実際の着手がf5以外だった
 * 場合は、 座標を適当な形に変換してf5から開始したのと同じ形にしないと定石を扱
 * えない 1. 最初に打たれた手をf5になるように変換 2. 定石手から打つ手を探す 3.
 * 手が見つかったらその手を最初に行った変換の逆変換を行って実際に打つ手を決める
 */
class CoordinatesTransformer {
	private int Rotate = 0;
	private boolean Mirror = false;
	public CoordinatesTransformer(Point first) {
		if (first.equals(new Point("d3"))) {
			Rotate = 1;
			Mirror = true;
		} else if (first.equals(new Point("c4"))) {
			Rotate = 2;
		} else if (first.equals(new Point("e6"))) {
			Rotate = -1;
			Mirror = true;
		}
	}
	// 座標をf5を開始点とする座標系に正規化する
	public Point normalize(Point p) {
		Point newp = rotatePoint(p, Rotate);
		if (Mirror)
			newp = mirrorPoint(newp);
		return newp;
	}
	// f5を開始点とする座標を本来の座標に戻す
	public Point denormalize(Point p) {
		Point newp = new Point(p.x, p.y);
		if (Mirror)
			newp = mirrorPoint(newp);
		newp = rotatePoint(newp, -Rotate);
		return newp;
	}
	/**
	 * 座標の回転
	 */
	private Point rotatePoint(Point old_point, int rotate) {
		rotate %= 4;
		if (rotate < 0) {
			rotate += 4;
		}
		Point new_point = new Point();
		switch (rotate) {
		case 1:
			new_point.x = old_point.y;
			new_point.y = Board.BOARD_SIZE - old_point.x + 1;
			break;
		case 2:
			new_point.x = Board.BOARD_SIZE - old_point.x + 1;
			new_point.y = Board.BOARD_SIZE - old_point.y + 1;
			break;
		case 3:
			new_point.x = Board.BOARD_SIZE - old_point.y + 1;
			new_point.y = old_point.x;
			break;
		default: // 0
			new_point.x = old_point.x;
			new_point.y = old_point.y;
			break;
		}
		return new_point;
	}
	/**
	 * 左右反転
	 */
	private Point mirrorPoint(Point point) {
		Point new_point = new Point();
		new_point.x = Board.BOARD_SIZE - point.x + 1;
		new_point.y = point.y;
		return new_point;
	}
}
/**
 * 石数を格納するクラス 石数を保存しておくオブジェクトを作る事で、 いちいちボー
 * ド全体の石数を数え上げる必要がなく処理が効率化
 */
class ColorStorage {
	private int data[] = new int[3];
	public int get(int color) {
		return data[color + 1];
	}
	public void set(int color, int value) {
		data[color + 1] = value;
	}
}
/**
 * ボードクラス ボードの表現、ボードの状態を管理し、 石を打つ操作や元に戻す操作
 * などを提供ボードの内部表現は、 色を表す変数の配列にします。
 */
class Board {
	// 方向を表す実数 フラグLEFTを立てるとき
	// dir =| LEFT ; OR(|)を使うと、
	// 1にしたいビットだけを1にする事が出来る。
	// フラグRIGHTが経っているかどうかを調べる
	// dir && RIGHT != 0 (RIGHTがたっている）
	private static final int NONE = 0; // 石を打てない
	private static final int UPPER = 1; // 上方向に石を裏返せる
	private static final int UPPER_LEFT = 2; // 左上に石を裏返せる
	private static final int LEFT = 4; // 左に石を裏返せる
	private static final int LOWER_LEFT = 8; // 左下に石を裏返せる
	private static final int LOWER = 16; // 下に石を裏返せる
	private static final int LOWER_RIGHT = 32; // 右下に石を裏返せる
	private static final int RIGHT = 64; // 右に石を裏返せる
	private static final int UPPER_RIGHT = 128; // 右上に石を裏返せる
	public static final int BOARD_SIZE = 8; // ボードサイズ
	public static final int MAX_TURNS = 60;
	// 壁も含めるため+2とする
	private int RawBoard[][] = new int[BOARD_SIZE + 2][BOARD_SIZE + 2];
	private int Turns; // 手数(0からはじまる)
	private int CurrentColor; // 現在のプレイヤー
	// 変更点を記録するvector
	// 変更点を記録しておきその情報を元に状態を復元する。
	private Vector UpdateLog = new Vector();
	// 打てる位置の座標を格納した３次元配列vector
	// 思考ルーチンが打てる手を全てリストアップするような場合に便利
	private Vector MovablePos[] = new Vector[MAX_TURNS + 1];
	// 打てる点を記録しておく
	private int MovableDir[][][] = new int[MAX_TURNS + 1][BOARD_SIZE + 2][BOARD_SIZE + 2];
	// 各色の石の数 class ColorStorage()
	private ColorStorage Discs = new ColorStorage();
	/**
	 * コンストラクタ
	 */
	public Board() {
		for (int i = 0; i <= MAX_TURNS; i++) { // Vectorの配列を初期化
			MovablePos[i] = new Vector();
		}
		// public void init() ;
		// ボードを初期化
		init();
	}
	/**
   * discで指定された座標に、disc.colorの色の石を打てるかどうか、また、 どの方
   * 向に石を裏返せるかを判定する。石を裏返せる方向にフラグが立った整数値が返る。
   * 石を打てるかどうか判定するアルゴリズム ・その位置が空きマスでなければ打て
   * ない・ある方向について、隣接する石が打とうとしている石と 違う色でなければ
   * 打てない ・その方向にいくつか相手の色が続き、最後に打とうとしている色があ
   * れば打てる
	 */
	private int checkMobility(Disc disc) {
		// 既に石があったら置けない
		if (RawBoard[disc.x][disc.y] != Disc.EMPTY) {
			return NONE;
		}
		int x, y;
		int dir = NONE;
		// 上
		// 自分が置こうとしている石は黒だとする
		// まず一つ上のマスに「置こうとしている石と逆の色の石」（白石）が
		// あるかどうかを調べる。なければ、上方向に石を裏返す事は出来ない。
		// RawBoard[disc.x][disc.y - 1]は一つ上
		// -disc.colorは逆の色 という意味
		if (RawBoard[disc.x][disc.y - 1] == -disc.color) { // 白石なら
			x = disc.x;
			y = disc.y - 2; // 上を見る
			// 白石が続く限り順に辿っていく。
			while (RawBoard[x][y] == -disc.color) {
				y--;
			}
			/**
       * ループが終了したとき RawBoard[x][y]は 白石以外の「黒石」「空き」「壁」
       * のいずれかである。もしこの位置が「黒石」であったら、 白石を黒石で挟め
       * るので「黒石」をここに打てる事になる。 上後方に石を裏返せる事が判った
       * のでフラグdirに上方向を表すビット「UPPER」をたてる。 残りの７方向につ
       * いても同様の手順で調べていく。
			 */
			if (RawBoard[x][y] == disc.color) { // 黒石なら
				dir |= UPPER; // 上方向に裏返せる
			}
		}
		// 下 disc.y+1
		if (RawBoard[disc.x][disc.y + 1] == -disc.color) {
			x = disc.x;
			y = disc.y + 2;
			while (RawBoard[x][y] == -disc.color) {
				y++;
			}
			if (RawBoard[x][y] == disc.color) {
				dir |= LOWER;
			}
		}
		// 左 disc.x-1
		if (RawBoard[disc.x - 1][disc.y] == -disc.color) {
			x = disc.x - 2;
			y = disc.y;
			while (RawBoard[x][y] == -disc.color) {
				x--;
			}
			if (RawBoard[x][y] == disc.color) {
				dir |= LEFT;
			}
		}
		// 右 disc.x+1
		if (RawBoard[disc.x + 1][disc.y] == -disc.color) {
			x = disc.x + 2;
			y = disc.y;
			while (RawBoard[x][y] == -disc.color) {
				x++;
			}
			if (RawBoard[x][y] == disc.color) {
				dir |= RIGHT;
			}
		}
		// 右上 disc.x+1 disc.y-1
		if (RawBoard[disc.x + 1][disc.y - 1] == -disc.color) {
			x = disc.x + 2;
			y = disc.y - 2;
			while (RawBoard[x][y] == -disc.color) {
				x++;
				y--;
			}
			if (RawBoard[x][y] == disc.color) {
				dir |= UPPER_RIGHT;
			}
		}
		// 左上
		if (RawBoard[disc.x - 1][disc.y - 1] == -disc.color) {
			x = disc.x - 2;
			y = disc.y - 2;
			while (RawBoard[x][y] == -disc.color) {
				x--;
				y--;
			}
			if (RawBoard[x][y] == disc.color) {
				dir |= UPPER_LEFT;
			}
		}
		// 左下
		if (RawBoard[disc.x - 1][disc.y + 1] == -disc.color) {
			x = disc.x - 2;
			y = disc.y + 2;
			while (RawBoard[x][y] == -disc.color) {
				x--;
				y++;
			}
			if (RawBoard[x][y] == disc.color) {
				dir |= LOWER_LEFT;
			}
		}
		// 右下
		if (RawBoard[disc.x + 1][disc.y + 1] == -disc.color) {
			x = disc.x + 2;
			y = disc.y + 2;
			while (RawBoard[x][y] == -disc.color) {
				x++;
				y++;
			}
			if (RawBoard[x][y] == disc.color) {
				dir |= LOWER_RIGHT;
			}
		}
		return dir;
	}
	/**
	 * pointで指定された位置に石を打つ 処理が成功したらtrue、失敗したらfalseが返る
	 */
	public boolean move(Point point) {
		// １．まず石が打てる位置かどうかを判定する
		// 打てない位置だったらエラー
		// 石が打てるかどうかの判断は、配列MovableDirを見て行う。
		// 座標の値が正しい範囲であるかどうかも確認
		if (point.x <= 0 || point.x > BOARD_SIZE) {
			return false;
		}
		if (point.y <= 0 || point.y > BOARD_SIZE) {
			return false;
		}
		if (MovableDir[Turns][point.x][point.y] == NONE) {
			return false;
		}
		// private void flipDiscs()
		// ２．指定された位置に石を打ち、
		// 変更点を格納するためのvector, updateに座標を格納する
		// ３．MovableDirを参照しながら石を裏返していく。
		// 石を裏返した座標はupdateに順次格納していく
		// ４．石を打ったり裏返したりした場合には、
		// 石数を表す変数を増やしたり減らしたりする。
		// ５．updateをUpdateLogに追加する
		flipDiscs(point);
		// ６．現在の手数、手番の色などの情報を更新する
		Turns++;
		CurrentColor = -CurrentColor;
		// private void initMovable()
		// ７．MovableDirとMovablePosを調べ直す
		initMovable();
		return true;
	}
	/**
	 * 着手可能な手を調べる処理 MovableDir及びMovablePosを初期化する
	 * MovalePos[Turns]とMovableDir[Turms]を再計算する
	 */
	private void initMovable() {
		Disc disc;
		int dir;
		MovablePos[Turns].clear();
		for (int x = 1; x <= BOARD_SIZE; x++) {
			for (int y = 1; y <= BOARD_SIZE; y++) {
				disc = new Disc(x, y, CurrentColor);
				// private ini checkMobility() ;
				dir = checkMobility(disc);
				if (dir != NONE) { // 置ける
					MovablePos[Turns].add(disc);
				}
				MovableDir[Turns][x][y] = dir;
			}
		}
	}
	/**
	 * 実際に石を帰す処理 pointで指定された位置に石を打ち、挟み込める全ての石を裏返す。
	 * 「打った石」と「裏返した石」をUpdateLogに挿入する。
	 */
	private void flipDiscs(Point point) {
		int x, y;
		int dir = MovableDir[Turns][point.x][point.y];
		Vector update = new Vector();
		RawBoard[point.x][point.y] = CurrentColor;
		update.add(new Disc(point.x, point.y, CurrentColor));
		// 上
		if ((dir & UPPER) != NONE) { // 上に置ける
			y = point.y;
			while (RawBoard[point.x][--y] != CurrentColor) {
				RawBoard[point.x][y] = CurrentColor;
				update.add(new Disc(point.x, y, CurrentColor));
			}
		}
		// 下
		if ((dir & LOWER) != NONE) {
			y = point.y;
			while (RawBoard[point.x][++y] != CurrentColor) {
				RawBoard[point.x][y] = CurrentColor;
				update.add(new Disc(point.x, y, CurrentColor));
			}
		}
		// 左
		if ((dir & LEFT) != NONE) {
			x = point.x;
			while (RawBoard[--x][point.y] != CurrentColor) {
				RawBoard[x][point.y] = CurrentColor;
				update.add(new Disc(x, point.y, CurrentColor));
			}
		}
		// 右
		if ((dir & RIGHT) != NONE) {
			x = point.x;
			while (RawBoard[++x][point.y] != CurrentColor) {
				RawBoard[x][point.y] = CurrentColor;
				update.add(new Disc(x, point.y, CurrentColor));
			}
		}
		// 右上
		if ((dir & UPPER_RIGHT) != NONE) {
			x = point.x;
			y = point.y;
			while (RawBoard[++x][--y] != CurrentColor) {
				RawBoard[x][y] = CurrentColor;
				update.add(new Disc(x, y, CurrentColor));
			}
		}
		// 左上
		if ((dir & UPPER_LEFT) != NONE) {
			x = point.x;
			y = point.y;
			while (RawBoard[--x][--y] != CurrentColor) {
				RawBoard[x][y] = CurrentColor;
				update.add(new Disc(x, y, CurrentColor));
			}
		}
		// 左下
		if ((dir & LOWER_LEFT) != NONE) {
			x = point.x;
			y = point.y;
			while (RawBoard[--x][++y] != CurrentColor) {
				RawBoard[x][y] = CurrentColor;
				update.add(new Disc(x, y, CurrentColor));
			}
		}
		// 右下
		if ((dir & LOWER_RIGHT) != NONE) {
			x = point.x;
			y = point.y;
			while (RawBoard[++x][++y] != CurrentColor) {
				RawBoard[x][y] = CurrentColor;
				update.add(new Disc(x, y, CurrentColor));
			}
		}
		// 石の数を更新
		// updateに入っている石の個数が今打たれた石の数なので、
		// 今売った石の個数にこの個数を加えます。
		// また、裏返された石の分だけ、裏返された色の石数を減らします。
		// 全体として空きマスは一つ減るので、空きマスの個数を一つ減らします。
		int discdiff = update.size();
		Discs.set(CurrentColor, Discs.get(CurrentColor) + discdiff);
		Discs.set(-CurrentColor, Discs.get(-CurrentColor) - (discdiff - 1));
		Discs.set(Disc.EMPTY, Discs.get(Disc.EMPTY) - 1);
		UpdateLog.add(update);
	}
	/**
   * ゲームが終了しているかどうかを判定する ゲームが終了していればtrue 終了して
   * いなければfalseをかえす
	 */
	public boolean isGameOver() {
		// 60手に達していたらゲーム終了
		if (Turns == MAX_TURNS) {
			return true;
		}
		// 打てる手があるならゲーム終了ではない
		if (MovablePos[Turns].size() != 0) {
			return false;
		}
		// 現在の手番と逆の色が打てるかどうか調べる
		Disc disc = new Disc();
		disc.color = -CurrentColor;
		for (int x = 1; x <= BOARD_SIZE; x++) {
			disc.x = x;
			for (int y = 1; y <= BOARD_SIZE; y++) {
				disc.y = y;
				// 置ける箇所が1つでもあればゲーム終了ではない
				if (checkMobility(disc) != NONE) {
					return false;
				}
			}
		}
		return true;
	}
	/**
   * パスする。成功したらtrueが返る。 パスできない場合（打つ手がある場合）はfalse
   * が返る moveに似ているが石を裏返さない分単純になっている
	 */
	public boolean pass() {
		// 1. 打つ手があればパスできない
		if (MovablePos[Turns].size() != 0) {
			return false;
		}
		// 2. ゲームが終了しているなら、パスできない
		// public boolean isGameOver()
		if (isGameOver()) {
			return false;
		}
		CurrentColor = -CurrentColor;
		// 3. 空のupdateを挿入しておく
		UpdateLog.add(new Vector());
		// private void initMovable()
		// 4. 色を相手側の色にする
		// 5. MovableDirとMovablePosを調べ直す
		initMovable();
		return true;
	}
	/**
   * 一つ前の手の状態に戻す。成功するとtrueが返る。 元に戻せない場合、すなわち
   * まだ一手も打っていない場合はfalseが返る。ボードを一つ前の状態に戻す機能は
   * リバーシ・ゲームにとって必須です。
	 */
	public boolean undo() {
		if (Turns == 0) { // ゲーム開始地点ならもう戻れない
			return false;
		}
		CurrentColor = -CurrentColor;
		Vector update = (Vector) UpdateLog.remove(UpdateLog.size() - 1);
		// 前回がパスだった場合は二つのデータを作り直す。
		// 前回がパスだったという事は置ける場所がなかったという事なので、
		// 全てのパスについてMovabeDirをNONEに、MovablePosを空にする
		if (update.isEmpty()) {
			// MovablePos及びMovableDirを再構築
			// 前回がパスだったという事は、置ける場所がなかったという事なので、
			// すべてのマスについてMovableDirをNoneに設定し
			// MovablePosを空にします。
			MovablePos[Turns].clear();
			for (int x = 1; x <= BOARD_SIZE; x++) {
				for (int y = 1; y <= BOARD_SIZE; y++) {
					MovableDir[Turns][x][y] = NONE;
				}
			}
		} else {
			// 前回がパスでなければUpdateLogの末尾すなわち一つ前の手の
			// updateに格納されている座標を元に石の並びを復元
			// vectorの先頭を「新しく置いた石」として座標を空きマス(EMPTY)
			// にし、それに続く座標の色を元に戻していきます。
			Turns--;
			Point p = (Point) update.get(0); // 石を元に戻す
			RawBoard[p.x][p.y] = Disc.EMPTY;
			for (int i = 1; i < update.size(); i++) {
				p = (Point) update.get(i);
				RawBoard[p.x][p.y] = -CurrentColor;
			}
			int discdiff = update.size(); // 石数の更新
			Discs.set(CurrentColor, Discs.get(CurrentColor) - discdiff);
			Discs.set(-CurrentColor, Discs.get(-CurrentColor) + (discdiff - 1));
			Discs.set(Disc.EMPTY, Discs.get(Disc.EMPTY) + 1);
		}
		return true;
	}
	/**
   * ボードをゲーム開始直後の状態にする。 Boardクラスのインスタンスが生成された
   * 直後は、コンストラクタによって同様の初期化処理が呼ばれているので、 initを
   * 呼ぶ必要はない。
	 */
	public void init() {
		// 全マスを空きマスに設定
		for (int x = 1; x <= BOARD_SIZE; x++) {
			for (int y = 1; y <= BOARD_SIZE; y++) {
				RawBoard[x][y] = Disc.EMPTY;
			}
		}
		// 壁の設定
		for (int y = 0; y < BOARD_SIZE + 2; y++) {
			RawBoard[0][y] = Disc.WALL;
			RawBoard[BOARD_SIZE + 1][y] = Disc.WALL;
		}
		for (int x = 0; x < BOARD_SIZE + 2; x++) {
			RawBoard[x][0] = Disc.WALL;
			RawBoard[x][BOARD_SIZE + 1] = Disc.WALL;
		}
		// 初期配置
		RawBoard[4][4] = Disc.WHITE;
		RawBoard[5][5] = Disc.WHITE;
		RawBoard[4][5] = Disc.BLACK;
		RawBoard[5][4] = Disc.BLACK;
		// 石数の初期設定
		Discs.set(Disc.BLACK, 2);
		Discs.set(Disc.WHITE, 2);
		Discs.set(Disc.EMPTY, BOARD_SIZE * BOARD_SIZE - 4);
		// 手数は0から数える
		Turns = 0;
		// 先手は黒
		CurrentColor = Disc.BLACK;
		// private void initMovable() ;
		initMovable();
	}
	/**
   * 定石を打つためには、 これまでにどのような手が打たれてきたかを知る必要があ
   * る vectorが空かどうかで場合分けしているのは、もしゲーム開始後一手も打って
   * いない状態で このメソッドが呼ばれたときに、 最後の要素を返そうとするとエラー
   * になるからです。vectorが空だった場合には、 新しいvectorを生成して返すよう
   * にしています。
	 */
	public Vector getHistory() {
		Vector history = new Vector();
		for (int i = 0; i < UpdateLog.size(); i++) {
			Vector update = (Vector) UpdateLog.get(i);
			if (update.isEmpty()) {
				continue; // パスは飛ばす
			}
			history.add(update.get(0));
		}
		return history;
	}
	/**
	 * そのほかのメソッド
	 */
	// 指定された石の個数を返す colorで指定された色の石の数を数える。
	public int countDisc(int color) {
		return Discs.get(color);
	}
	// pointで指定した座標の石の色を調べる
	public int getColor(Point point) {
		return RawBoard[point.x][point.y];
	}
	// 石を打てる座標が並んだvectorを返す
	public Vector getMovablePos() {
		return MovablePos[Turns];
	}
	// 直前の手で返された石が並んだvectorを返す
	public Vector getUpdate() {
		if (UpdateLog.isEmpty()) {
			return new Vector();
		} else {
			return (Vector) UpdateLog.lastElement();
		}
	}
	// 現在の手番の色を返す
	public int getCurrentColor() {
		return CurrentColor;
	}
	// 現在の手数を返す
	public int getTurns() {
		return Turns;
	}
	// 仮
	public int getLiberty(Point p) {
    return 0 ;
	}
}
/**
 * 石を表現するクラス Pointクラスを継承
 */
class Disc extends Point {
	// 石を表す定数
	public static final int BLACK = 1; // 黒
	public static final int EMPTY = 0; // 空
	public static final int WHITE = -1; // 白
	public static final int WALL = 2; // 壁
	public int color;
	// コンストラクタ
	public Disc() {
		super(0, 0);
		this.color = EMPTY;
	}
	/**
	 * 色を表す定数値の設定
	 * @param x 横軸
	 * @param y 縦軸
	 * @param color ステータス
	 */
	public Disc(int x, int y, int color) {
		super(x, y);
		this.color = color;
	}
}
/**
 * 石の表現 石の位置を指定するために座標系を定義 ボードの左上a1の位置を(1,1)と
 * し、横軸をx 縦軸をyとします。
 */
class Point {
	public int x; // 横軸
	public int y; // 縦軸 横に行くたびにY座標が増える
	public Point() { // コンストラクタ
		this(0, 0);
	}
	public Point(int x, int y) {// コンストラクタ
		this.x = x;
		this.y = y;
	}
	public Point(String coord) throws IllegalArgumentException {
		// 文字列が２文字に満たない場合は警告
		if (coord == null || coord.length() < 2) {
			throw new IllegalArgumentException("The argument must be Reversi style coordinates!");
		}
		x = coord.charAt(0) - 'a' + 1;
		y = coord.charAt(1) - '1' + 1;
	}
	public String toString() {
		String coord = new String();
		coord += (char) ('a' + x - 1);
		coord += (char) ('1' + y - 1);
		return coord;
	}
	public boolean equals(Point p) {
		if (this.x != p.x) {
			return false;
		}
		if (this.y != p.y) {
			return false;
		}
		return true;
	}
}
/**
 * コンソールへの出力
 */
class ConsoleBoard extends Board {
	public void print() {

		System.out.println("   A_|B_|C_|D_|E_|F_|G_|H_");
		for (int y = 1; y <= 8; y++) {
			System.out.print(" " + y);
			for (int x = 1; x <= 8; x++) {
				switch (getColor(new Point(x, y))) {
				case Disc.BLACK:
					System.out.print("|#_");
					break;
				case Disc.WHITE:
					System.out.print("|@_");
					break;
				default:
					System.out.print("|__");
					break;
				}
			}
			System.out.print(y);
			System.out.println();
		}
		System.out.println("   A_|B_|C_|D_|E_|F_|G_|H_");
	}
}
/**
 * メインクラス
 */
class Othello {
	public static void main(String[] args) {
		// HumanPlayer extends Player
		// AIPlayer extends Player
		Player[] player = new Player[2]; // interface
		int current_player = 0;
		// ConsoleBoard extends Board
		ConsoleBoard board = new ConsoleBoard(); // extends class Board
		// コマンドラインオプション -r が与えられるとコンピュータ先手にする
		boolean reverse = false;
		if (args.length > 0) {
			if (args[0].equals("-r")) {
				reverse = true;
			}
		}
    // 先手・後手の設定
		if (reverse) { 
			player[0] = new AIPlayer();
			player[1] = new HumanPlayer();
		} else {
			player[0] = new HumanPlayer();
			player[1] = new AIPlayer();
		}
		while (true) { // とりあえず無限ループ
			board.print(); // ボードレイアウトを出力
			try {
				player[current_player].onTurn(board);
				// class UndoException
			} catch (UndoException e) {
				do {
					board.undo();
					board.undo();
				} while (board.getMovablePos().isEmpty());
				continue;
				// class ExitException
			} catch (ExitException e) {
				return;
				// class GameOverException
			} catch (GameOverException e) {
				System.out.println("ゲーム終了");
				System.out.print("黒石" + board.countDisc(Disc.BLACK) + " ");
				System.out.println("白石" + board.countDisc(Disc.WHITE));
				return;
			} catch (Exception e) {
				System.out.println("Unexpected exception: " + e);
				return;
			}
			current_player = ++current_player % 2; // プレイヤーの交代
		}
	}
}
