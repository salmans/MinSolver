package MinSolver;

/* 
 * Kodkod -- Copyright (c) 2005-2007, Emina Torlak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.IConstr;
import org.sat4j.specs.TimeoutException;

import kodkod.ast.Formula;
import kodkod.ast.Relation;
import kodkod.engine.Cost;
import kodkod.engine.config.Options;
import kodkod.engine.fol2sat.HigherOrderDeclException;
import kodkod.engine.fol2sat.Translation;
import kodkod.engine.fol2sat.TranslationLog;
import kodkod.engine.fol2sat.Translator;
import kodkod.engine.fol2sat.TrivialFormulaException;
import kodkod.engine.fol2sat.UnboundLeafException;
import kodkod.engine.satlab.SATAbortedException;
import kodkod.engine.satlab.SATMinSolver;
import kodkod.engine.satlab.SATProver;
import kodkod.engine.satlab.SATSolver;
import kodkod.instance.Bounds;
import kodkod.instance.Instance;
import kodkod.instance.Tuple;
import kodkod.instance.TupleFactory;
import kodkod.instance.TupleSet;
import kodkod.util.ints.IntIterator;
import kodkod.util.ints.IntSet;


/** 
 * A computational engine for solving relational formulae.
 * A {@link kodkod.ast.Formula formula} is solved with respect to given 
 * {@link kodkod.instance.Bounds bounds} and {@link kodkod.engine.config.Options options}.
 * 
 * @specfield options: Options 
 * @author Emina Torlak 
 */
public final class MinSolver {
	private final Options options;

	/**
	 * Constructs a new Solver with the default options.
	 * @effects this.options' = new Options()
	 */
	public MinSolver() {
		this.options = new Options();
	}

	/**
	 * Constructs a new Solver with the given options.
	 * @effects this.options' = options
	 * @throws NullPointerException - options = null
	 */
	public MinSolver(Options options) {
		if (options == null)
			throw new NullPointerException();
		this.options = options;
	}

	/**
	 * Returns the Options object used by this Solver
	 * to guide translation of formulas from first-order
	 * logic to cnf.
	 * @return this.options
	 */
	public Options options() {
		return options;
	}

	//TODO Remove after test
	public Translation getTranslation(Iterator<MinSolution> iterator){
		MinSolutionIterator theIterator = (MinSolutionIterator)iterator;
		return theIterator.translation;
	}
	
	/**
	 * Attempts to satisfy the given formula with respect to the specified bounds, while
	 * minimizing the specified cost function.
	 * If the operation is successful, the method returns a Solution that contains either a minimal-cost
	 * instance of the formula or a proof of unsatisfiability.  The latter is generated iff 
	 * the SAT solver generated by this.options.solver() is a {@link SATProver SATProver} in  addition
	 * to being a {@link kodkod.engine.satlab.SATMinSolver SATMinSolver}.
	 * 
	 * @requires this.options.logTranslation==0 && this.options.solver.minimizer
	 * @return Solution to the formula with respect to the given bounds and cost
	 * @throws NullPointerException - formula = null || bounds = null || cost = null
	 * @throws kodkod.engine.fol2sat.UnboundLeafException - the formula contains an undeclared variable or
	 * a relation not mapped by the given bounds
	 * @throws kodkod.engine.fol2sat.HigherOrderDeclException - the formula contains a higher order declaration that cannot
	 * be skolemized, or it can be skolemized but this.options.skolemize is false.
	 * @throws AbortedException - this solving task was interrupted with a call to Thread.interrupt on this thread
	 * @throws IllegalArgumentException -  some  (formula.^children & Relation) - cost.relations
	 * @throws IllegalStateException - !this.options.solver.minimizer || this.options.logTranslation
	 * @see Solution
	 * @see Options
	 * @see Cost
	 */
	public MinSolution solve(Formula formula, Bounds bounds, Cost cost)
			throws HigherOrderDeclException, UnboundLeafException, MinAbortedException {
		
		if (options.logTranslation()>0 || !options.solver().minimizer())
			throw new IllegalStateException();
		
		final long startTransl = System.currentTimeMillis();
		try {
			
			final Translation translation = Translator.translate(formula, bounds, options);
			final long endTransl = System.currentTimeMillis();

			final SATMinSolver cnf = (SATMinSolver)translation.cnf();
			for(Relation r : bounds.relations()) {
				IntSet vars = translation.primaryVariables(r);
				if (vars != null) {
					int rcost = cost.edgeCost(r);
					for(IntIterator iter = vars.iterator();  iter.hasNext(); ) {
						cnf.setCost(iter.next(), rcost);
					}
				}
			}
			
			options.reporter().solvingCNF(0, cnf.numberOfVariables(), cnf.numberOfClauses());
			final long startSolve = System.currentTimeMillis();
			final boolean isSat = cnf.solve();
			final long endSolve = System.currentTimeMillis();

			final MinStatistics stats = new MinStatistics(translation, endTransl - startTransl, endSolve - startSolve);
			
			return isSat ? sat(bounds, translation, stats) : unsat(translation, stats);
		} catch (TrivialFormulaException trivial) {
			final long endTransl = System.currentTimeMillis();
			return trivial(bounds, trivial, endTransl - startTransl);
		} catch (SATAbortedException sae) {
			throw new MinAbortedException(sae);
		}
	}

	/**
	 * Attempts to satisfy the given formula with respect to the specified bounds or
	 * prove the formula's unsatisfiability.
	 * If the operation is successful, the method returns a Solution that contains either
	 * an instance of the formula or an unsatisfiability proof.  Note that an unsatisfiability
	 * proof will be constructed iff this.options specifies the use of a core extracting SATSolver.
	 * Additionally, the CNF variables in the proof can be related back to the nodes in the given formula 
	 * iff this.options has translation logging enabled.  Translation logging also requires that 
	 * there are no subnodes in the given formula that are both syntactically shared and contain free variables.  
	 * 
	 * @return Solution to the formula with respect to the given bounds
	 * @throws NullPointerException - formula = null || bounds = null
	 * @throws kodkod.engine.fol2sat.UnboundLeafException - the formula contains an undeclared variable or
	 * a relation not mapped by the given bounds
	 * @throws kodkod.engine.fol2sat.HigherOrderDeclException - the formula contains a higher order declaration that cannot
	 * be skolemized, or it can be skolemized but this.options.skolemize is false.
	 * @throws AbortedException - this solving task was interrupted with a call to Thread.interrupt on this thread
	 * @see Solution
	 * @see Options
	 * @see Proof
	 */
	public MinSolution solve(Formula formula, Bounds bounds)
			throws HigherOrderDeclException, UnboundLeafException, MinAbortedException {
		
		final long startTransl = System.currentTimeMillis();
		
		try {		
		
			final Translation translation = Translator.translate(formula, bounds, options);
			final long endTransl = System.currentTimeMillis();

			final SATSolver cnf = translation.cnf();
			
			options.reporter().solvingCNF(translation.numPrimaryVariables(), cnf.numberOfVariables(), cnf.numberOfClauses());
			final long startSolve = System.currentTimeMillis();
			final boolean isSat = cnf.solve();
			final long endSolve = System.currentTimeMillis();

			final MinStatistics stats = new MinStatistics(translation, endTransl - startTransl, endSolve - startSolve);
			return isSat ? sat(bounds, translation, stats) : unsat(translation, stats);
			
		} catch (TrivialFormulaException trivial) {
			final long endTransl = System.currentTimeMillis();
			return trivial(bounds, trivial, endTransl - startTransl);
		} catch (SATAbortedException sae) {
			throw new MinAbortedException(sae);
		}
	}
	
	/**
	 * Attempts to find all solutions to the given formula with respect to the specified bounds or
	 * to prove the formula's unsatisfiability.
	 * If the operation is successful, the method returns an iterator over n Solution objects. The outcome
	 * of the first n-1 solutions is SAT or trivially SAT, and the outcome of the nth solution is UNSAT
	 * or tirivally  UNSAT.  Note that an unsatisfiability
	 * proof will be constructed for the last solution iff this.options specifies the use of a core extracting SATSolver.
	 * Additionally, the CNF variables in the proof can be related back to the nodes in the given formula 
	 * iff this.options has variable tracking enabled.  Translation logging also requires that 
	 * there are no subnodes in the given formula that are both syntactically shared and contain free variables.  
	 * 
	 * @return an iterator over all the Solutions to the formula with respect to the given bounds
	 * @throws NullPointerException - formula = null || bounds = null
	 * @throws kodkod.engine.fol2sat.UnboundLeafException - the formula contains an undeclared variable or
	 * a relation not mapped by the given bounds
	 * @throws kodkod.engine.fol2sat.HigherOrderDeclException - the formula contains a higher order declaration that cannot
	 * be skolemized, or it can be skolemized but this.options.skolemize is false.
	 * @throws AbortedException - this solving task was interrupted with a call to Thread.interrupt on this thread
	 * @throws IllegalStateException - !this.options.solver().incremental()
	 * @see Solution
	 * @see Options
	 * @see Proof
	 */
	public Iterator<MinSolution> solveAll(final Formula formula, final Bounds bounds) 
		throws HigherOrderDeclException, UnboundLeafException, MinAbortedException {
		
		if (!options.solver().incremental())
			throw new IllegalArgumentException("cannot enumerate solutions without an incremental solver.");
						
		return new MinSolutionIterator(formula, bounds, options);
		
	}

	public Iterator<MinSolution> lift(final Formula formula, final Bounds bounds, Translation translation,
			MinSolution solution, Instance lifters) 
			throws HigherOrderDeclException, UnboundLeafException, MinAbortedException {
			//Create lifters from solution and lifters.
			//Create a solution iterator.
			//Return the solution iterator.
		
			ArrayList<Integer> allLifters = new ArrayList<Integer>();
			Map<Relation, TupleSet> solutionTuples = solution.instance().relationTuples();
			Map<Relation, TupleSet> lifterTuples = lifters.relationTuples();
			
			//This can be a method!
			for(Relation r : solutionTuples.keySet()){
				TupleSet tuples = solutionTuples.get(r);
				for(Tuple t: tuples){
					allLifters.add(MinTwoWayTranslator.getPropVariableForTuple(bounds, translation, r, t));
				}
			}
			
			for(Relation r : solutionTuples.keySet()){
				TupleSet tuples = lifterTuples.get(r);
				if(tuples != null)
					for(Tuple t: tuples){
						allLifters.add(MinTwoWayTranslator.getPropVariableForTuple(bounds, translation, r, t));
					}
			}			
						
			if (!options.solver().incremental())
				throw new IllegalArgumentException("cannot enumerate solutions without an incremental solver.");
					
			return new MinSolutionIterator(formula, bounds, options, allLifters);
			
		}	
	
	/**
	 * Returns the lifters for the current model loaded in an iterator.
	 * @param iterator the iterator.
	 * @return the lifters tuple relations of an instance.
	 * @throws TimeoutException
	 * @throws ContradictionException
	 */
	//TODO This method can be accessible in a more intuitive way.
	public Instance getLifters(Iterator<MinSolution> iterator) throws TimeoutException, ContradictionException{
		MinSolutionIterator theIterator = (MinSolutionIterator)iterator;
		return MinTwoWayTranslator.translatePropositions(
				theIterator.translation, theIterator.bounds, theIterator.getLifters());
	}

	/**
	 * {@inheritDoc}
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		return options.toString();
	}
	
	/**
	 * Returns the result of solving a sat formula.
	 * @param bounds Bounds with which  solve() was called
	 * @param translation the translation
	 * @param stats translation / solving stats
	 * @return the result of solving a sat formula.
	 */
	private static MinSolution sat(Bounds bounds, Translation translation, MinStatistics stats) {
		final MinSolution sol = MinSolution.satisfiable(stats, padInstance(translation.interpret(), bounds));
		translation.cnf().free();
		return sol;
	}

	/**
	 * Returns the result of solving an unsat formula.
	 * @param translation the translation 
	 * @param stats translation / solving stats
	 * @return the result of solving an unsat formula.
	 */
	private static MinSolution unsat(Translation translation, MinStatistics stats) {
		final SATSolver cnf = translation.cnf();
		final TranslationLog log = translation.log();
		if (cnf instanceof SATProver && log != null) {
			return MinSolution.unsatisfiable(stats, new MinResolutionBasedProof((SATProver) cnf, log));
		} else { // can free memory
			final MinSolution sol = MinSolution.unsatisfiable(stats, null);
			cnf.free();
			return sol;
		}
	}
	
	/**
	 * @return the number of primary variables needed to encode the unknown tuples in the given bounds.
	 */
	private static int trivialPrimaries(Bounds bounds) { 
		int prim = 0;
		for(Relation r : bounds.relations()) { 
			prim += bounds.upperBound(r).size() - bounds.lowerBound(r).size();
		}
		return prim;
	}
	
	/**
	 * Returns the result of solving a trivially (un)sat formula.
	 * @param bounds Bounds with which solve()  was called
	 * @param desc TrivialFormulaException thrown as the result of the formula simplifying to a constant
	 * @param translTime translation time
	 * @return the result of solving a trivially (un)sat formula.
	 */
	private static MinSolution trivial(Bounds bounds, TrivialFormulaException desc, long translTime) {
		final MinStatistics stats = new MinStatistics(trivialPrimaries(desc.bounds()), 0, 0, translTime, 0);
		if (desc.value().booleanValue()) {
			return MinSolution.triviallySatisfiable(stats, padInstance(toInstance(desc.bounds()), bounds));
		} else {
			return MinSolution.triviallyUnsatisfiable(stats, trivialProof(desc.log()));
		}
	}
	
	/**
	 * Returns a proof for the trivially unsatisfiable log.formula,
	 * provided that log is non-null.  Otherwise returns null.
	 * @requires log != null => log.formula is trivially unsatisfiable
	 * @return a proof for the trivially unsatisfiable log.formula,
	 * provided that log is non-null.  Otherwise returns null.
	 */
	private static MinProof trivialProof(TranslationLog log) {
		return log==null ? null : new MinTrivialProof(log);
	}
	
	/**
	 * "Pads" the argument instance with the mappings that occur in bounds.lowerBound
	 * but not in the instance. 
	 * @requires instance.relations in bounds.relations
	 * @effects instance.relations' = bounds.relations' &&
	 *          instance.tuples' = bounds.lowerBound ++ instance.tuples
	 * @return instance
	 */
	private static Instance padInstance(Instance instance, Bounds bounds) {
		for (Relation r : bounds.relations()) {
			if (!instance.contains(r)) {
				instance.add(r, bounds.lowerBound(r));
			}
		}
		for (IntIterator iter = bounds.ints().iterator(); iter.hasNext();) {
			int i = iter.next();
			instance.add(i, bounds.exactBound(i));
		}
		return instance;
	}

	/**
	 * Creates an instance from the given Bounds.  The instance
	 * is simply the mapping bounds.lowerBound.
	 * @return the instance corresponding to bounds.lowerBound
	 */
	private static Instance toInstance(Bounds bounds) {
		final Instance instance = new Instance(bounds.universe());
		for (Relation r : bounds.relations()) {
			instance.add(r, bounds.lowerBound(r));
		}
		for (IntIterator iter = bounds.ints().iterator(); iter.hasNext();) {
			int i = iter.next();
			instance.add(i, bounds.exactBound(i));
		}
		return instance;
	}
	
	/**
	 * An iterator over all solutions of a model.
	 * @author Emina Torlak
	 */
	private static final class MinSolutionIterator implements Iterator<MinSolution> {
		private final Options options;
		private Formula formula;
		private Bounds bounds;
		private Translation translation;
		private long translTime;
		private int trivial;
		
		//Modifications for minimal models
		private Boolean sat = null;
		/**
		 * Keeps cone restriction clauses such that the next model from the SATsolver
		 * is not in any of the previous cones.
		 */
		private Set<List<Integer>> coneRestrictionClauses = new HashSet<List<Integer>>();
		/**
		 * Keeps a list of all the constraints that are being taken into account.
		 * Some operations such as getLifters() has to eliminate all the constraints.
		 * Also, when another iterator is using the solver, the constraints from
		 * the current iterator should be removed.
		 */
		private Set<IConstr> coneRestrictionConstraints = new HashSet<IConstr>();

		/**
		 * The lifters for this iteration.
		 */
		private final int[] lifters;
		
		/**
		 * Constructs a solution iterator for the given formula, bounds, and options.
		 */
		MinSolutionIterator(Formula formula, Bounds bounds, Options options) {
			this(formula, bounds, options, null);
		}

		/**
		 * Constructs a solution iterator for the given formula, bounds, options and lifters.
		 * @param formula
		 * @param bounds
		 * @param options
		 * @param lifters
		 */
		MinSolutionIterator(Formula formula, Bounds bounds, Options options, ArrayList<Integer> lifters) {
			this.formula = formula;
			this.bounds = bounds;
			this.options = options;
			this.translation = null;
			this.trivial = 0;
			this.lifters = (lifters == null) ? null : toIntCollection(lifters);
		}
		
		/**
		 * Returns true if there is another solution.
		 * @see java.util.Iterator#hasNext()
		 */
		public boolean hasNext() {
			return formula != null;
		}
		/**
		 * Solves translation.cnf and adds the negation of the
		 * found model to the set of clauses.
		 * @requires this.translation != null
		 * @effects this.translation.cnf is modified to eliminate
		 * the  current solution from the set of possible solutions
		 * @return current solution
		 */
		private MinSolution nonTrivialSolution() {
			try {
				final SATSolver cnf = translation.cnf();
				
				try{
					// If all the previous constraints have been removed by another operation,
					// add them again.
					if(coneRestrictionConstraints.isEmpty()){
						addAllClauses();
					}
				}
				catch(ContradictionException e){
					System.err.println(e.getMessage());
				}
				
				options.reporter().solvingCNF(translation.numPrimaryVariables(), cnf.numberOfVariables(), cnf.numberOfClauses());				
				final long startSolve = System.currentTimeMillis();
				final boolean isSat = solve();//cnf.solve();
				final long endSolve = System.currentTimeMillis();

				final MinStatistics stats = new MinStatistics(translation, translTime, endSolve - startSolve);
				if (isSat) {
					// extract the current solution; can't use the sat(..) method because it frees the sat solver
					final MinSolution sol = MinSolution.satisfiable(stats, padInstance(translation.interpret(), bounds));
					// add the negation of the current model to the solver
					final int primary = translation.numPrimaryVariables();
					final ArrayList<Integer> notModel = new ArrayList<Integer>();
					
					//Do not return any other model in the cone of the new model.
					//TODO Tuples as variable assignments cause problems here. They are among primary variables
					//but they must not be considered.
					for(int i = 1; i <= primary; i++){
						if(cnf.valueOf(i)){
							notModel.add(-i);
						}
					}
					
					try{						
						coneRestrictionClauses.add(notModel);
						coneRestrictionConstraints.add(((MinSATSolver)cnf).addConstraint(toIntCollection(notModel)));
					}
					catch(ContradictionException e){
						System.err.println(e.getMessage());
					}
					
					return sol;
				} else {
					formula = null; bounds = null; // unsat, no more solutions, free up some space
					return unsat(translation, stats);
				}
			} catch (SATAbortedException sae) {
				throw new MinAbortedException(sae);
			}
		}
		
		/**
		 * Packages the information from the given trivial formula exception
		 * into a solution and returns it.  If the formula is satisfiable, 
		 * this.formula and this.bounds are modified to eliminate the current
		 * trivial solution from the set of possible solutions.
		 * @requires this.translation = null
		 * @effects this.formula and this.bounds are modified to eliminate the current
		 * trivial solution from the set of possible solutions.
		 * @return current solution
		 */
		private MinSolution trivialSolution(TrivialFormulaException tfe) {
			final MinStatistics stats = new MinStatistics(0, 0, 0, translTime, 0);
			if (tfe.value().booleanValue()) {
				trivial++;
				final Bounds translBounds = tfe.bounds();
				final Instance trivialInstance = padInstance(toInstance(translBounds), bounds);
				final MinSolution sol = MinSolution.triviallySatisfiable(stats, trivialInstance);
				
				final List<Formula> changes = new LinkedList<Formula>();
								
				for(Map.Entry<Relation, TupleSet> entry: trivialInstance.relationTuples().entrySet()) {
					final Relation r = entry.getKey();
					
					if (!translBounds.relations().contains(r)) { 
						translBounds.bound(r, bounds.lowerBound(r), bounds.upperBound(r));
					}
					
					if (translBounds.lowerBound(r)!=translBounds.upperBound(r)) { // r may change
						if (entry.getValue().isEmpty()) { 
							changes.add(r.some());
						} else {
							final Relation rmodel = Relation.nary(r.name()+"_"+trivial, r.arity());
							translBounds.boundExactly(rmodel, entry.getValue());	
							changes.add(r.eq(rmodel).not());
						}
					}
				}
				
				bounds = translBounds;
				
				// no changes => there can be no more solutions (besides the current trivial one)
				formula = changes.isEmpty() ? Formula.FALSE : tfe.formula().and(Formula.or(changes));
				
				return sol;
			} else {
				formula = null; bounds = null;
				return MinSolution.triviallyUnsatisfiable(stats, trivialProof(tfe.log()));
			}
		}
		/**
		 * Returns the next solution if any.
		 * @see java.util.Iterator#next()
		 */
		public MinSolution next() {
			if (!hasNext()) throw new NoSuchElementException();
			if (translation==null) {
				try {
					translTime = System.currentTimeMillis();
					translation = Translator.translate(formula, bounds, options);
					translTime = System.currentTimeMillis() - translTime;
					return nonTrivialSolution();
				} catch (TrivialFormulaException tfe) {
					translTime = System.currentTimeMillis() - translTime;
					return trivialSolution(tfe);
				} 
			} else {
				return nonTrivialSolution();
			}
		}		
		
		/**
		 * @see java.util.Iterator#remove()
		 */
		public void remove() {
			throw new UnsupportedOperationException();
		}
		
		/**
		 * Prepares a minimal model.
		 * @return true if there is a next solution; otherwise, false.
		 */
		private boolean solve(){
			try{
				if(lifters == null)
					sat = Boolean.valueOf(((MinSATSolver)translation.cnf()).solve());
				else
					sat = Boolean.valueOf(((MinSATSolver)translation.cnf()).solve(lifters));

				if(sat)
				{	
					try{		
						minimize();
					}
					catch(ContradictionException e)
					{System.err.println(e.getMessage());}
				}
				
				return sat;
			} catch (org.sat4j.specs.TimeoutException e) {
				throw new RuntimeException("timed out");
			}
		}

		/**
		 * Minimizes the model in the SAT solver.
		 * @throws TimeoutException
		 * @throws ContradictionException
		 */
		private void minimize() throws TimeoutException, ContradictionException{
			//This keeps constraints to be removed from the solver
			//after finding the next model.
			ArrayList<IConstr> constraints = new ArrayList<IConstr>();
			//All the unit clauses being passed to the solver as assumptions.
			
			//Salman: The cloning operation can be improved. 
			ArrayList<Integer> unitClauses = toArrayList(lifters);
						
			do
			{				
				// Given that candidate for minimal-model, try to make something smaller.
				// add: disjunction of negations of all positive literals in M (constraint)
				// add: all negative literals as unit clauses
				
				// An array of the next constraint being added.
				ArrayList<Integer> constraint = new ArrayList<Integer>();
				//TODO Aagain, we should get rid of assignment tuples.
				for(int i = 1; i <= translation.numPrimaryVariables(); i++){
					boolean value = translation.cnf().valueOf(i);
					if(value == true)
						constraint.add(-i);
					else
						unitClauses.add(-i);
				}
				
				//System.out.println("constraint: "+constraint);
				constraints.add(((MinSATSolver)translation.cnf()).addConstraint(toIntCollection(constraint)));
			}
			while(Boolean.valueOf(((MinSATSolver)translation.cnf()).solve(toIntCollection(unitClauses))));
			
			// Remove all the cone-specific constraints we just added from the solver:
			Iterator<IConstr> it = constraints.iterator();
			while(it.hasNext()){
				((MinSATSolver)translation.cnf()).removeConstraint(it.next());		
			}
			
			// Do NOT add a constraint here to force the solver out of this cone. 
			// Do that in the next solve() call. We want to leave open the possibility
			// of landing in this cone again to support lifting/exporation!
			
		}
		
		/**
		 * Computes all the lifters for the current model loaded in the solver.
		 * @return
		 * @throws TimeoutException
		 * @throws ContradictionException
		 */
		public int[] getLifters() throws TimeoutException, ContradictionException{
			MinSATSolver solver = (MinSATSolver)translation.cnf();
			
			Set<Integer> wantToAdd = new HashSet<Integer>();
			ArrayList<Integer> retVal = new ArrayList<Integer>();
			List<Integer> preservedFacts = new ArrayList<Integer>();
			
			removeAllConstraints();
			
			// preservedFacts are the positive literals that define the "cone" we are in.
			// wantToAdd are the negative (turned positive) literals we want to check for in the cone.
			//TODO Again, primary variables.
			for(int i = 1; i <= translation.numPrimaryVariables(); i++){
				if(solver.valueOf(i))
					preservedFacts.add(i);
				else
					wantToAdd.add(i);
			}
					
			boolean wasSatisfiable = false;
			List<Integer> unitClauses = new ArrayList<Integer>(preservedFacts);
			// Loop while (a) there are facts left to find and (b) still satisfiable.
			do
			{
				// Add a disjunction for the current set of literals we want to find:
				IConstr removeWTA = null;
				//System.out.println("Adding WTA: "+wantToAdd);
				if(wantToAdd.size() > 1)
				{
					removeWTA = solver.addConstraint(toIntCollection(wantToAdd));
				}
				else
				{
					for(Integer onlyOne : wantToAdd)
						unitClauses.add(onlyOne);
				}
				
				wasSatisfiable = solver.solve(toIntCollection(unitClauses));		
				
				if(wasSatisfiable)
				{
					
					//System.out.println("Model found was: "+Arrays.toString(tempModel));
					Set<Integer> foundLifters = new HashSet<Integer>(); // avoid concurrentmodificationexception
					for(Integer toAdd : wantToAdd)
					{
						// The -1 is because the model is an array (start = 0) 
						// yet our variables start=1
						if(solver.valueOf(toAdd))
						{
							foundLifters.add(toAdd);
							retVal.add(toAdd);
						}
					}

					wantToAdd.removeAll(foundLifters);
				}	
				
				// Remove the targets for this iteration (may not be needed?)
				if(removeWTA != null)
					solver.removeConstraint(removeWTA);
			}
			while(wantToAdd.size() > 0 && wasSatisfiable);
			
			return toIntCollection(retVal);
		}
		
		
		//Helpers:
		/**
		 * Considers all the constraints.
		 * @throws ContradictionException
		 */
		private void addAllClauses() throws ContradictionException{
			Iterator<List<Integer>> it = coneRestrictionClauses.iterator();
			while(it.hasNext()){
				coneRestrictionConstraints.add(((MinSATSolver)translation.cnf()).addConstraint(toIntCollection(it.next())));	
			}
		}
		
		/**
		 * Removes all the cone restriction constraints of this iterator.
		 */
		private void removeAllConstraints(){
			Iterator<IConstr> it = coneRestrictionConstraints.iterator();
			while(it.hasNext()){
				((MinSATSolver)translation.cnf()).removeConstraint(it.next());
				it.remove();
			}
		}		
		
		private int[] toIntCollection(Collection<Integer> integers)
		{
		    int[] ret = new int[integers.size()];
		    int iIndex = 0;
		    for(Integer anInt : integers)
		    {
		        ret[iIndex] = anInt;
		    	iIndex++;	    	
		    }
		    return ret;
		}
		
		/**
		 * Converts a list of integers to an ArrayList.
		 * @param list the list.
		 * @return the ArrayList containing the elements of list.
		 */
		private ArrayList<Integer> toArrayList(int[] list){
			ArrayList<Integer> retVal = new ArrayList<Integer>();
			if(list != null){
				for(int i = 0; i < list.length; i++){
					retVal.add(list[i]);
				}
			}
			
			return retVal;
		}
	}
	
	/**
	 * Handles the translation of propositional elements to relational facts and vice versa.
	 * @author Salman
	 *
	 */
	public static class MinTwoWayTranslator {
		/**
		 * Converts a set of primary propositional variables into set of relational expressions.
		 * @param translation the translation.
		 * @param bounds the bounds.
		 * @param theVars a VectInt of the variables to convert.
		 * @return
		 */
		private static Instance translatePropositions(Translation translation, Bounds bounds, int[] theVars)
				//throws MInternalNoBoundsException
		{
			
			// Populate an empty instance over the universe we're using:
			Instance result = new Instance(bounds.universe());

			//////////////////////////////////////////////////////////////////////////
			//TODO
			// Very inefficient! Cache this later. Do in advance of this function!!!
			// E.g. map 158 ---> R if 158 represents <tup> in R for some <tup>.
			Map<Integer, Relation> mapVarToRelation = new HashMap<Integer, Relation>(); 
			for(Relation r : bounds.relations())
			{
				IntSet varsForThisRelation = translation.primaryVariables(r);
				IntIterator itVarsForThis = varsForThisRelation.iterator();
				while(itVarsForThis.hasNext())
				{
					mapVarToRelation.put(itVarsForThis.next(), r);
				}
					
			}
			//////////////////////////////////////////////////////////////////////////
			
			// Now that we have the mapping from var --> its relation, we can find the tuple:		
			for(int i = 0; i < theVars.length; i++)
			{			
				int theVar = theVars[i];
				Relation myRelation = mapVarToRelation.get(theVar);
				IntSet s = translation.primaryVariables(myRelation);		
				Tuple myTuple = getTupleForPropVariable(
							bounds, translation, s, myRelation, theVar);
					
				// .add here is actually .set -- it overwrites what is already in the relation.
				// So we CANNOT just do:			
				//result.add(myRelation, bounds.universe().factory().setOf(myTuple));
				
				TupleSet currentContents = result.tuples(myRelation);
				TupleSet theContents = bounds.universe().factory().setOf(myTuple);
				if(currentContents != null) // returns null instead of empty set!!
					theContents.addAll(currentContents); 
				
				result.add(myRelation, theContents);
			}
			
			
			// Set<RelationalFact> would be better than Instance. But for now use Instance
			
			// Return an instance (should not be interpreted as a model of the qry!!) that contains
			// only those relational facts indicated by theVars
			return result;				
		}

		
		private static Tuple getTupleForPropVariable(Bounds theBounds, Translation theTranslation, IntSet s, Relation r, int theVar)
		//throws MInternalNoBoundsException
		{		        
			// The relation's upper bound has a list of tuple indices. The "index" variable below is an index
			// into that list of indices. (If our upper bound was _everything_, would be no need to de-reference.)
			
	        int minVarForR = s.min();    	
			
			// Compute index: (variable - minvariable) of this relation's variables 
	        int index = theVar - minVarForR;                            
	                                
	        // OPT: How slow is this? May want to cache...
	        int[] arr = theBounds.upperBound(r).indexView().toArray();
	        
	        TupleFactory factory = theBounds.universe().factory();   
	        Tuple tup = factory.tuple(r.arity(), arr[index]);  

	        //MCommunicator.writeToLog("\ngetTupleForPropVariable: thisTuple="+tup+" for "+theVar+". Relation had vars: "+s+" and the offset was "+minVarForR+
	        //		"leaving index="+index+". Upper bound indexview: "+Arrays.toString(arr)+
	        //		"\nThe de-referenced tuple index is: "+arr[index]);

	        return tup;
		}
		
		private static int getPropVariableForTuple(Bounds bounds, Translation translation, Relation r, Tuple tuple){
			IntSet s = translation.primaryVariables(r);
			return s.min() + tuple.index();
		}
	}
	
}
