/*
 * File:    $HeadURL$
 * Version: $LastChangedRevision$
 * Date:    $Date$
 * Author:  $LastChangedBy$
 *
 * JVoiceXML - A free VoiceXML implementation.
 *
 * Copyright (C) 2008-2014 JVoiceXML group - http://jvoicexml.sourceforge.net
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */
package org.jvoicexml.processor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jvoicexml.processor.grammar.Grammar;
import org.jvoicexml.processor.grammar.Rule;
import org.jvoicexml.processor.grammar.RuleComponent;
import org.jvoicexml.processor.grammar.RuleParse;
import org.jvoicexml.processor.grammar.RuleReference;
import org.jvoicexml.processor.grammar.RuleSpecial;
import org.jvoicexml.processor.grammar.RuleTag;
import org.jvoicexml.processor.grammar.RuleToken;
import org.jvoicexml.processor.srgs.GrammarException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides a means to perform evaluations on a parsed grammar.
 *
 * @author Bernd Kiefer
 * @version $Revision$
 * @since 0.7
 */
public abstract class AbstractParser {
  private static final Logger log = LoggerFactory.getLogger(AbstractParser.class);

  protected static int gen = 0;

  static interface TreeWalker {

    void enter(ChartNode node, boolean leaf);

    void leave(ChartNode node, boolean leaf);
  }

  // A chart node structure, a replacement for the rule walker
  public static class ChartNode {

    int start, end, dot, id;
    RuleComponent rule;
    // multiple parents needed, in case added multiple times
    List<ChartNode> children;
    List<ChartNode> equivs;
    //ChartNode parent;

    public RuleComponent getRule() {
      return rule;
    }

    /** Constructor, for use with other constructors */
    protected ChartNode(int s, int e, RuleComponent r, int d) {
      id = ++gen;
      start = s;
      end = e;
      rule = r;
      dot = d;
      children = new ArrayList<ChartNode>();
    }

    /** Constructor for predicts */
    protected ChartNode(int s, RuleComponent r) {
      // if it's an epsilon, it's passive (dot == -1)
      this(s, s, r,
          (r.equals(RuleSpecial.NULL) || (r instanceof RuleTag)
              //|| (r instanceof RuleToken && ((RuleToken)r).isEpsilon())
              ? -1 : 0));
    }

    /** Constructor combining active and passive item (only AbstractParser)
     */
    protected ChartNode(ChartNode active, ChartNode passive) {
      this(active.start, passive.end, active.rule,
          active.rule.nextSlot(active.dot));
      children.addAll(active.children);
      children.add(passive);
    }

    /** Constructor for "left corner predicts" that combine a RuleComponent with
     *  dot at position zero and a passive ChartNode
     *  Only used by LeftCornerParser
     */
    protected ChartNode(RuleComponent r, ChartNode passive) {
      // The first item of r has already be filled, and span is the same as the
      // tokNode
      this(passive.getStart(), passive.getEnd(), r, r.nextSlot(0));
      children.add(passive);
    }

    /** Constructor advancing the dot for RuleSequence when the next item is
     *  a RuleTag (only LeftCornerParser)
     */
    protected ChartNode(int pos, RuleTag tag) {
      this(pos, pos, tag, -1);
    }

    // TODO: would be nicer if we had a "graphical" dot, but for that, we would
    // need functions to print the rule with a dot argument
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append('(').append(Integer.toString(id)).append(':')
          .append(Integer.toString(start)).append(',')
          .append(Integer.toString(end)).append(',')
          .append(Integer.toString(dot)).append(" <");
      for (ChartNode c : children) {
        sb.append(c == null ? "r" : Integer.toString(c.id)).append(' ');
      }
      sb.append("> ").append(rule).append(')');
      return sb.toString();
    }

    public void printTree(String indent) {
      System.out.println(indent + this);
      for (ChartNode child : children) {
        child.printTree(indent + "  ");
      }
    }

    public void preorder(TreeWalker acceptor) {
      acceptor.enter(this, children.isEmpty());
      for (ChartNode child : children) {
        child.preorder(acceptor);
      }
      acceptor.leave(this, children.isEmpty());
    }

    public boolean equals(ChartNode c) {
      return (start == c.start && end == c.end && rule.equals(c.rule)
          && dot == c.dot);
    }

    public boolean equalsChildren(ChartNode c) {
      Iterator<ChartNode> it = c.children.iterator();
      for (ChartNode child : children) {
        if (child.id != it.next().id) return false;
      }
      return true;
    }

    public boolean isPassive() {
      return dot < 0;
    }

    public List<ChartNode> getChildren() { return children; }

    public int getId() { return id; }

    public int getDot() { return dot; }

    public int getStart() { return start; }

    public int getEnd() { return end; }
  }

  public static boolean useLeftCorner = true;

  public static AbstractParser getParser(final GrammarManager grammarManager) {
    return useLeftCorner ? new LeftCornerParser(grammarManager)
        : new ChartGrammarChecker(grammarManager);
  }

  private final GrammarManager manager;

  /** Lazy expansion of RuleReference, to avoid tainting the RuleReference with
   * grammar resolution code.
   */
  private final Map<RuleReference, RuleParse> resolved;

  /** An agenda */
  private final Deque<ChartNode> agenda;

  /** The current input */
  protected String[] input;

  /** A chart */
  protected List<ChartNode>[] chartIn, chartOut;

  /** The currently used Grammar */
  protected Grammar grammar;

  /** Constructs a new GrammarChecker.
   *
   * @param grammarManager the grammar manager.
   */
  protected AbstractParser(final GrammarManager grammarManager) {
    manager = grammarManager;
    resolved = new HashMap<RuleReference, RuleParse>();
    agenda = new ArrayDeque<ChartNode>();
  }

  protected final RuleComponent getResolved(RuleComponent c) {
    return (c instanceof RuleParse)
      ? ((RuleParse) c).getRuleReference()
      : c;
  }

  public Stream<ChartNode> returnAllResults() {
    List<ChartNode> fromZero = getOutEdges(0);
    if (null == fromZero) {
      return Stream.empty();
    }
    final Rule rule = grammar.getRule(grammar.getRoot());
    RuleComponent compo = rule.getRuleComponent();
    if (compo instanceof RuleReference) {
      compo = resolved.get(compo);
    }
    final RuleComponent component = compo;
    return fromZero
        .stream()
        .filter(c -> c.end == input.length && c.rule == component);
  }

  public ChartNode returnFirstResult() {
    return returnAllResults().findFirst().orElse(null);
  }

  protected final boolean agendaNotEmpty() {
    return ! agenda.isEmpty();
  }

  protected final void addToAgenda(ChartNode c) {
    agenda.add(c);
  }

  protected final ChartNode agendaPop() {
    return agenda.pop();
  }


  @SuppressWarnings("unchecked")
  protected final RuleComponent initParse(Grammar gram, String[] in)
      throws GrammarException {
    agenda.clear();
    resolved.clear();
    input = in;
    chartIn = new ArrayList[in.length + 1];
    chartOut = new ArrayList[in.length + 1];
    grammar = gram;
    final String root = grammar.getRoot();
    final Rule rule = grammar.getRule(root);
    if (rule == null) {
      // TODO: SHOULD NEVER HAPPEN, CAUGHT WHEN READING GRAMMAR!
      throw new GrammarException("Undefined rule referenced: " + root);
    }
    return rule.getRuleComponent();
  }

  /** Add a chart node, checking specific preconditions beforehand */
  protected abstract void add(ChartNode c);

  /**
   * Checks if the given tokens can be represented using the given graph.
   *
   * @param grammar the grammar to check
   * @param input the tokens
   * @return <code>true</code> if the tokens are valid.
   * @throws GrammarException
   */
  public abstract ChartNode parse(final Grammar gram, final String[] in)
      throws GrammarException;

  /** Return the input string covered by the chart node n */
  public String covered (ChartNode n) {
    StringBuilder sb = new StringBuilder();
    for(int i = n.start; i < n.end; ++i) {
      sb.append(input[i]).append(' ');
    }
    if (n.start != n.end)
      sb.deleteCharAt(sb.length()-1); // delete trailing space
    return sb.toString();
  }

  /** Access with lazy initialization of chart heads */
  protected final List<ChartNode> getEdges(List<ChartNode>[] heads, int pos) {
    List<ChartNode> out = heads[pos];
    if (null == out) {
      out = new ArrayList<ChartNode>();
      heads[pos] = out;
    }
    return out;
  }

  /** Check if there exists an equivalent chart node already and return true
   *  if it's not been already in the chart, otherwise add it to the equivs
   *  field of the representative edge
   * @param edges the edge list to add the chart edge to (potentially)
   * @param c the new chart edge
   * @return true if this is a truely new edge, false otherwise
   */
  protected final boolean checkEquiv(List<ChartNode> edges, ChartNode c) {
    for (ChartNode x : edges) {
      if (x.equals(c)) {
        if (x.equalsChildren(c)) {
          log.debug("Identical chart node produced: {}", c);
        } else {
          if (null == x.equivs) {
            x.equivs = new ArrayList<ChartNode>();
          }
          x.equivs.add(c);
        }
        return false;
      }
    }
    edges.add(c);
    return true;
  }

  protected final RuleComponent resolve(RuleComponent r) throws GrammarException {
    if (r instanceof RuleReference) {
      // resolve r and replace it by the resolved proxy, a RuleParse
      if (resolved.containsKey(r)) {
        r = resolved.get(r);
      } else {
        final RuleReference reference = (RuleReference) r;
        final Rule rule = manager.resolve(reference);
        if (rule == null) {
          // TODO: SHOULD NEVER HAPPEN, SHOULD BE CAUGHT WHEN GRAMMAR IS READ
          throw new GrammarException("Invalid rule reference: "
              + reference.getRepresentation());
        }
        final RuleComponent component = rule.getRuleComponent();
        final RuleParse rp = new RuleParse(reference, component);
        resolved.put(reference, rp);
        r = rp;
      }
    }
    return r;
  }

  protected final void addPrediction(int pos, RuleComponent r)
      throws GrammarException {
    add(new ChartNode(pos, resolve(r)));
  }

  /**
   * This is rather a scan than predict. It directly creates a passive item,
   * if possible.
   *
   * @param token
   * @param current
   */
  protected final void scan(final RuleToken token, int start) {
    int pos = start;
    Pattern p = token.getPattern();
    if (p == null) {
      final String text = token.getText();
      final String[] tokens = text.split(" ");
      for (String tok : tokens) {
        if (pos >= input.length) {
          return;
        }
        final String currentInput = input[pos];
        if (!tok.equalsIgnoreCase(currentInput)) {
          return;
        }
        ++pos;
      }
    } else {
      if (pos >= input.length) {
        return;
      }
      final String currentInput = input[pos];
      if (! p.matcher(currentInput).matches()) {
        return;
      }
      ++pos;
    }

    // now, for the first time, we add a complete token
    add(new ChartNode(start, pos, token, -1));
  }

  /********************** For displaying the chart **********************/

  public int chartSize() {
    return chartOut.length;
  }

  public List<ChartNode> getOutEdges(int i) {
    return chartOut[i];
  }

  public List<ChartNode> getInEdges(int i) {
    return chartIn[i];
  }
}
