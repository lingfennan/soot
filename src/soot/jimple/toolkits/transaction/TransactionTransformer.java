package soot.jimple.toolkits.transaction;

import java.util.*;

import soot.*;
import soot.util.Chain;
import soot.jimple.*;
import soot.jimple.toolkits.pointer.*;
import soot.jimple.toolkits.callgraph.*;
import soot.jimple.spark.pag.*;
import soot.toolkits.scalar.*;
import soot.toolkits.graph.*;
import soot.toolkits.mhp.*;
import soot.toolkits.mhp.pegcallgraph.*;
import soot.toolkits.mhp.findobject.*;
import soot.toolkits.mhp.stmt.*;
import soot.tagkit.LineNumberTag;


public class TransactionTransformer extends SceneTransformer
{
    public TransactionTransformer(Singletons.Global g){MHPLists = null;}
    public static TransactionTransformer v() 
	{ 
		return G.v().soot_jimple_toolkits_transaction_TransactionTransformer();
	}
	
	boolean optionPrintGraph = false;
	boolean optionPrintTable = false;
	boolean optionPrintDebug = false;
	
	List MHPLists;

    protected void internalTransform(String phaseName, Map options)
	{
    	Map methodToFlowSet = new HashMap();

		optionPrintGraph = PhaseOptions.getBoolean( options, "print-graph" );
		optionPrintTable = PhaseOptions.getBoolean( options, "print-table" );
		optionPrintDebug = PhaseOptions.getBoolean( options, "print-debug" );
		
    	// For all methods, run the intraprocedural analysis (transaction finder)
    	Iterator runAnalysisClassesIt = Scene.v().getApplicationClasses().iterator();
    	while (runAnalysisClassesIt.hasNext()) 
    	{
    	    SootClass appClass = (SootClass) runAnalysisClassesIt.next();
    	    Iterator methodsIt = appClass.getMethods().iterator();
    	    while (methodsIt.hasNext())
    	    {
    	    	SootMethod method = (SootMethod) methodsIt.next();
				if(method.isConcrete())
				{
	    	    	Body b = method.retrieveActiveBody();
    		    	
    	    		// run the intraprocedural analysis
    				TransactionAnalysis ta = new TransactionAnalysis(new ExceptionalUnitGraph(b), b, optionPrintDebug);
    				Chain units = b.getUnits();
    				Unit lastUnit = (Unit) units.getLast();
    				FlowSet fs = (FlowSet) ta.getFlowBefore(lastUnit);
    			
    				// add the results to the list of results
    				methodToFlowSet.put(method, fs);
				}
    	    }
    	}
    	
    	// Create a composite list of all transactions
    	List AllTransactions = new Vector();
    	Collection AllFlowSets = methodToFlowSet.values();
    	Iterator fsIt = AllFlowSets.iterator();
    	while(fsIt.hasNext())
    	{
    		FlowSet fs = (FlowSet) fsIt.next();
    		List fList = fs.toList();
    		for(int i = 0; i < fList.size(); i++)
    			AllTransactions.add(((TransactionFlowPair) fList.get(i)).tn);
    	}
    	
    	// Give each method a unique, deterministic identifier (based on an alphabetical sort by method name)
    	List methodNamesTemp = new ArrayList();
    	Iterator tnIt5 = AllTransactions.iterator();
    	while (tnIt5.hasNext()) 
    	{
    	    Transaction tn1 = (Transaction) tnIt5.next();
    	    String mname = tn1.method.getSignature(); //tn1.method.getSignature() + "." + tn1.method.getName();
    	    if(!methodNamesTemp.contains(mname))
    	    	methodNamesTemp.add(mname);
		}
		String methodNames[] = new String[1];
		methodNames = (String []) methodNamesTemp.toArray(methodNames);
		Arrays.sort(methodNames);

		// Identify each transaction with its method's identifier
		// this matrix is <# method names> wide and <max txns possible in one method> + 1 tall
		// might be possible to calculate a smaller size for this matrix
		int identMatrix[][] = new int[methodNames.length][Transaction.nextIDNum - methodNames.length + 2];
		for(int i = 0; i < methodNames.length; i++)
		{
			for(int j = 0; j < Transaction.nextIDNum - methodNames.length + 1; j++)
			{
				if(j != 0)
					identMatrix[i][j] = 50000;
				else
					identMatrix[i][j] = 0;
			}
		}
    	Iterator tnIt0 = AllTransactions.iterator();
    	while(tnIt0.hasNext())
    	{
    		Transaction tn1 = (Transaction) tnIt0.next();
			int methodNum = Arrays.binarySearch(methodNames, tn1.method.getSignature());
			identMatrix[methodNum][0]++;
			identMatrix[methodNum][identMatrix[methodNum][0]] = tn1.IDNum;
    	}
    	
    	for(int j = 0; j < methodNames.length; j++)
    	{
    		identMatrix[j][0] = 0; // set the counter to 0 so it sorts out (into slot 0).
    		Arrays.sort(identMatrix[j]); // sort this subarray
		}
		
    	Iterator tnIt4 = AllTransactions.iterator();
    	while(tnIt4.hasNext())
    	{
    		Transaction tn1 = (Transaction) tnIt4.next();
			int methodNum = Arrays.binarySearch(methodNames, tn1.method.getSignature());
			int tnNum = Arrays.binarySearch(identMatrix[methodNum], tn1.IDNum) - 1;
    		tn1.name = "m" + (methodNum < 10? "00" : (methodNum < 100? "0" : "")) + methodNum + "n" + (tnNum < 10? "0" : "") + tnNum;
    	}
		
    	// Determine the read/write set of each transaction using the list
    	// to create a TransactionAwareSideEffectAnalysis
    	// As is, this breaks atomicity: inner transaction's effects are visible
    	// as soon as the inner transaction completes. ("open nesting")
    	// As long as we're using "synchronized" keyword, this behavior is implied, so OK!
    	TransactionAwareSideEffectAnalysis tasea = 
    		new TransactionAwareSideEffectAnalysis(Scene.v().getPointsToAnalysis(), 
    				Scene.v().getCallGraph(), AllTransactions);
    	Iterator tnIt = AllTransactions.iterator();

    	while(tnIt.hasNext())
    	{
    		Transaction tn = (Transaction) tnIt.next();
			Body b = tn.method.retrieveActiveBody();
			UnitGraph g = new ExceptionalUnitGraph(b);
			LocalDefs sld = new SmartLocalDefs(g, new SimpleLiveLocals(g));
    		Iterator invokeIt = tn.invokes.iterator();
    		while(invokeIt.hasNext())
    		{
    			Stmt stmt = (Stmt) invokeIt.next();
    			
    			RWSet stmtRead = tasea.transactionalReadSet(tn.method, stmt, sld);
    			if(stmtRead != null)
	    			tn.read.union(stmtRead);
    			
    			RWSet stmtWrite = tasea.transactionalWriteSet(tn.method, stmt, sld);
    			if(stmtWrite != null)
	    			tn.write.union(stmtWrite);
    			
	    		if(stmtRead != null && stmtRead.size() > 10)
	    		{
	    			if(optionPrintDebug)
	    				G.v().out.println("Big Read Set: (" + stmtRead.size() + ")" + stmt);
	    		}
	    		if(stmtWrite != null && stmtWrite.size() > 10)
	    		{
	    			if(optionPrintDebug)
	    				G.v().out.println("Big Write Set: (" + stmtWrite.size() + ")" + stmt);
	    		}
	    	}
    	}
    	
    	// *** Find Stray Reads/Writes *** (DISABLED)
    	
    	// add external data races as one-line transactions
    	// note that finding them isn't that hard (though it is time consuming)
    	// however, actually adding entermonitor & exitmonitor statements is rather complex... must deal with exception handling!
    	// For all methods, run the intraprocedural analysis (transaction finder)
    	// Note that these will only be transformed if they are either added to
    	// methodToFlowSet or if a loop and new body transformer are used for methodToStrayRWSet
/*    	Map methodToStrayRWSet = new HashMap();
    	Iterator runRWFinderClassesIt = Scene.v().getApplicationClasses().iterator();
    	while (runRWFinderClassesIt.hasNext()) 
    	{
    	    SootClass appClass = (SootClass) runRWFinderClassesIt.next();
    	    Iterator methodsIt = appClass.getMethods().iterator();
    	    while (methodsIt.hasNext())
    	    {
    	    	SootMethod method = (SootMethod) methodsIt.next();
//    	    	G.v().out.println(method.toString());
    	    	Body b = method.retrieveActiveBody();
    	    	
    	    	// run the interprocedural analysis
    			PTFindStrayRW ptfrw = new PTFindStrayRW(new ExceptionalUnitGraph(b), b, AllTransactions);
    			Chain units = b.getUnits();
    			Unit firstUnit = (Unit) units.iterator().next();
    			FlowSet fs = (FlowSet) ptfrw.getFlowBefore(firstUnit);
    			
    			// add the results to the list of results
    			methodToStrayRWSet.put(method, fs);
    	    }
    	}
//*/    	

		// *** Get Parallel Execution Graph ***
		SootMethod mainMethod = Scene.v().getMainClass().getMethodByName("main");

		PointsToAnalysis pta = Scene.v().getPointsToAnalysis();
		if (!(pta instanceof PAG))
		{
		   System.err.println("You must use Spark for points-to analysis when computing MHP information (for transaction transformer)!");
		   System.exit(1);
		}
		CallGraph callGraph = Scene.v().getCallGraph();

		// Get a call graph trimmed to contain only the relevant methods (non-lib, non-native)
		PegCallGraph pecg = new PegCallGraph(callGraph);
	    
	    // Find allocation nodes that are run more than once
	    // Also find methods that are run more than once
		AllocNodesFinder anf = new AllocNodesFinder(pecg, callGraph, (PAG) pta);
		Set multiRunAllocNodes = anf.getMultiRunAllocNodes();
		Set multiCalledMethods = anf.getMultiCalledMethods();

		// Find Thread.start() and Thread.join() statements (in live code)
		StartJoinFinder sjf = new StartJoinFinder((PAG) pta); // does analysis
		Map startToAllocNodes = sjf.getStartToAllocNodes();
		Map startToRunMethods = sjf.getStartToRunMethods();
		Map startToContainingMethod = sjf.getStartToContainingMethod();
		Map startToJoin = sjf.getStartToJoin();
		
		// Build MHP Lists
		MHPLists = new ArrayList();
		Map containingMethodToThreadMethods = new HashMap();
		Iterator threadIt = startToRunMethods.entrySet().iterator();
		int threadNum = 0;
		while(threadIt.hasNext())
		{
			// Get list of possible Runnable.run methods (actually, a list of peg chains)
			// and a list of allocation sites for this thread start statement
			// and the thread start statement itself
			Map.Entry e = (Map.Entry) threadIt.next();
			Stmt startStmt = (Stmt) e.getKey();
			List runMethods = (List) e.getValue();
			List threadAllocNodes = (List) startToAllocNodes.get(e.getKey());

			// Get a list of all possible unique Runnable.run methods for this thread start statement
			List threadMethods = new ArrayList();
			Iterator runMethodsIt = runMethods.iterator();
			while(runMethodsIt.hasNext())
			{
				SootMethod method = (SootMethod) runMethodsIt.next();
				if(!threadMethods.contains(method))
					threadMethods.add(method);
			}
			
			// Get a list containing all methods in the call graph(s) rooted at the possible run methods for this thread start statement
			// AKA a list of all methods that might be called by the thread started here
			int methodNum = 0;
			while(methodNum < threadMethods.size()) // iterate over all methods in threadMethods, even as new methods are being added to it
			{
				Iterator succMethodsIt = pecg.getSuccsOf(threadMethods.get(methodNum)).iterator();
				while(succMethodsIt.hasNext())
				{
					SootMethod method = (SootMethod) succMethodsIt.next();
					// if all edges into this method are of Kind THREAD, ignore it 
					// (because it's a run method that won't be called as part of THIS thread)
					boolean ignoremethod = true;
					Iterator edgeInIt = callGraph.edgesInto(method);
					while(edgeInIt.hasNext())
					{
						Edge edge = (Edge) edgeInIt.next();
						if( edge.kind() != Kind.THREAD && threadMethods.contains(edge.src())) // called directly by any of the thread methods?
							ignoremethod = false;
					}
					if(!ignoremethod && !threadMethods.contains(method))
						threadMethods.add(method);
				}
				methodNum++;
			}
			
			// Add this list of methods to MHPLists
			MHPLists.add(threadMethods);
			if(optionPrintDebug)
				System.out.println("THREAD" + threadNum + ": " + threadMethods.toString());
			
			// Find out if the "thread" in "thread.start()" could be more than one object
			boolean mayStartMultipleThreadObjects = (threadAllocNodes.size() > 1);
			if(!mayStartMultipleThreadObjects) // if there's only one alloc node
			{
				if(multiRunAllocNodes.contains(threadAllocNodes.iterator().next())) // but it gets run more than once
				{
					mayStartMultipleThreadObjects = true; // then "thread" in "thread.start()" could be more than one object
				}
			}
			
			// Find out if the "thread.start()" statement may be run more than once
			SootMethod startStmtMethod = (SootMethod) startToContainingMethod.get(startStmt);
			boolean mayBeRunMultipleTimes = multiCalledMethods.contains(startStmtMethod); // if method is called more than once...
			if(!mayBeRunMultipleTimes)
			{
				UnitGraph graph = new CompleteUnitGraph(startStmtMethod.getActiveBody());
				MultiRunStatementsFinder finder = new MultiRunStatementsFinder(
					graph, startStmtMethod, multiCalledMethods, callGraph);
				FlowSet multiRunStatements = finder.getMultiRunStatements(); // list of all units that may be run more than once in this method
				if(multiRunStatements.contains(startStmt))
					mayBeRunMultipleTimes = true;
			}

			// If a run-many thread.start() statement is (always) associated with a join statement in the same method,
			// then it may be possible to treat it as run-once, if this method is non-reentrant and called only
			// by one thread (sounds strict, but actually this is the most common case)
			if(mayBeRunMultipleTimes && startToJoin.containsKey(startStmt))
			{
				mayBeRunMultipleTimes = false; // well, actually, we don't know yet
				methodNum = 0;
				List containingMethodCalls = new ArrayList();
				containingMethodCalls.add(startStmtMethod);
				while(methodNum < containingMethodCalls.size()) // iterate over all methods in threadMethods, even as new methods are being added to it
				{
					Iterator succMethodsIt = pecg.getSuccsOf(containingMethodCalls.get(methodNum)).iterator();
					while(succMethodsIt.hasNext())
					{
						SootMethod method = (SootMethod) succMethodsIt.next();
						if(method == startStmtMethod)
						{// this method is reentrant
							mayBeRunMultipleTimes = true; // this time it's for sure
							break;
						}
						if(!containingMethodCalls.contains(method))
							containingMethodCalls.add(method);
					}
					methodNum++;
				}
				if(!mayBeRunMultipleTimes)
				{// There's still one thing that might cause this to be run multiple times: if it can be run in parallel with itself
				 // but we can't find that out 'till we're done
					containingMethodToThreadMethods.put(startStmtMethod, threadMethods);
				}
			}

			// If more than one thread might be started at this start statement,
			// and this start statement may be run more than once,
			// then add this list of methods to MHPLists *AGAIN*
			if(optionPrintDebug)
				System.out.println("Start Stmt " + startStmt.toString() + 
					" mayStartMultipleThreadObjects=" + mayStartMultipleThreadObjects + " mayBeRunMultipleTimes=" + mayBeRunMultipleTimes);
			if(mayStartMultipleThreadObjects && mayBeRunMultipleTimes)
			{
				MHPLists.add(((ArrayList) threadMethods).clone());
				if(optionPrintDebug)
					System.out.println("THREAD-AGAIN" + threadNum + ": " + threadMethods.toString());
			}
			threadNum++;
		}

		// do same for main method
		List mainMethods = new ArrayList();
		MHPLists.add(mainMethods);
		mainMethods.add(mainMethod);
		// get all the successors, add to threadMethods
		int methodNum = 0;
		while(methodNum < mainMethods.size())
		{
			Iterator succMethodsIt = pecg.getSuccsOf(mainMethods.get(methodNum)).iterator();
			while(succMethodsIt.hasNext())
			{
				SootMethod method = (SootMethod) succMethodsIt.next();
				// if all edges into this are of Kind THREAD, ignore it
				boolean ignoremethod = true;
				Iterator edgeInIt = callGraph.edgesInto(method);
				while(edgeInIt.hasNext())
				{
					if( ((Edge) edgeInIt.next()).kind() != Kind.THREAD )
						ignoremethod = false;
				}
				if(!ignoremethod && !mainMethods.contains(method))
					mainMethods.add(method);
			}
			methodNum++;
		}
		if(optionPrintDebug)
			System.out.println("MAIN   : " + mainMethods.toString());
			
		// Revisit the containing methods of start-join pairs that are non-reentrant but might be called in parallel
		boolean addedNew = true;
		while(addedNew)
		{
			addedNew = false;
			Iterator it = containingMethodToThreadMethods.entrySet().iterator();
			while(it.hasNext())
			{
				Map.Entry e = (Map.Entry) it.next();
				SootMethod someStartMethod = (SootMethod) e.getKey();
				List someThreadMethods = (List) e.getValue();
				if(mayHappenInParallel(someStartMethod, someStartMethod))
				{
					MHPLists.add(((ArrayList) someThreadMethods).clone());
					containingMethodToThreadMethods.remove(someStartMethod);
					if(optionPrintDebug)
						G.v().out.println("THREAD-REVIVED: " + someThreadMethods);
					addedNew = true;
				}
			}
		}

    	// *** Calculate locking scheme ***
    	// Search for data dependencies between transactions, and split them into disjoint sets
    	int nextGroup = 1;
    	Iterator tnIt1 = AllTransactions.iterator();
    	while(tnIt1.hasNext())
    	{
    		Transaction tn1 = (Transaction) tnIt1.next();
    		
    		// if this transaction has somehow already been marked for deletion
    		if(tn1.setNumber == -1)
    			continue;
    		
    		// if this transaction is empty
    		if(tn1.read.size() == 0 && tn1.write.size() == 0)
    		{
    			// this transaction has no effect except on locals... we don't need it!
    			tn1.setNumber = -1; // AKA delete the transactional region (but don't really so long as we are using
    								// the synchronized keyword in our language... because java guarantees memory
    								// barriers at certain points in synchronized blocks)
    		}
    		else
    		{
	        	Iterator tnIt2 = AllTransactions.iterator();
	    		while(tnIt2.hasNext())
	    		{
	    			Transaction tn2 = (Transaction) tnIt2.next();
	    			
	    			// check if it's us!
	    			if(tn1 == tn2)
	    				continue;
	    			
	    			// check if this transactional region is going to be deleted
	    			if(tn2.setNumber == -1)
	    				continue;

	    			// check if they're already marked as having an interference
	    			// NOTE: this results in a sound grouping, but a badly 
	    			//       incomplete dependency graph. If the dependency 
	    			//       graph is to be analyzed, we cannot do this
//	    			if(tn1.setNumber > 0 && tn1.setNumber == tn2.setNumber)
//	    				continue;
	    			
	    			// check if these two transactions can't ever be in parallel
	    			if(!mayHappenInParallel(tn1, tn2))
	    				continue;

	    			// check for RW or WW data dependencies.
	    			if(tn1.write.hasNonEmptyIntersection(tn2.write) ||
	    					tn1.write.hasNonEmptyIntersection(tn2.read) ||
	    					tn1.read.hasNonEmptyIntersection(tn2.write))
	    			{
	    				// Determine the size of the intersection for GraphViz output
	    				CodeBlockRWSet rw = tn1.write.intersection(tn2.write);
	    				rw.union(tn1.write.intersection(tn2.read));
	    				rw.union(tn1.read.intersection(tn2.write));
	    				int size = rw.size();
	    				
	    				// Record this 
	    				tn1.edges.add(new DataDependency(tn2, size, rw));
//	    				tn2.edges.add(new DataDependency(tn1, size, rw)); // will be added in opposite direction later
	    				
	    				// if tn1 already is in a group
	    				if(tn1.setNumber > 0)
	    				{
	    					// if tn2 is NOT already in a group
	    					if(tn2.setNumber == 0)
	    					{
	    						tn2.setNumber = tn1.setNumber;
	    					}
	    					// if tn2 is already in a group
	    					else if(tn2.setNumber > 0)
	    					{
		    					int setToDelete = tn2.setNumber;
		    					int setToExpand = tn1.setNumber;
			    	        	Iterator tnIt3 = AllTransactions.iterator();
			    	    		while(tnIt3.hasNext())
			    	    		{
			    	    			Transaction tn3 = (Transaction) tnIt3.next();
			    	    			if(tn3.setNumber == setToDelete)
			    	    			{
			    	    				tn3.setNumber = setToExpand;
			    	    			}
			    	    		}	    						
	    					}
	    				}
	    				// if tn1 is NOT already in a group
	    				else if(tn1.setNumber == 0)
	    				{
	    					// if tn2 is NOT already in a group
	    					if(tn2.setNumber == 0)
		    				{
		    					tn1.setNumber = tn2.setNumber = nextGroup;
		    					nextGroup++;
		    				}
	    					// if tn2 is already in a group
	    					else if(tn2.setNumber > 0)
	    					{
	    						tn1.setNumber = tn2.setNumber;
	    					}
	    				}
	    			}
	    		}
	    		// If, after comparing to all other transactions, we have no group:
	    		if(tn1.setNumber == 0)
	    		{
	    			if(mayHappenInParallel(tn1, tn1))
	    			{
	    				tn1.setNumber = nextGroup;
	    				nextGroup++;
	    			}
	    			else
	    			{
	    				tn1.setNumber = -1; // delete transactional region
	    			}
	    		}	    			
    		}
    	}
    	
    	// Create an array of RW sets, one for each transaction group
    	// In each set, put the union of all RW Dependencies in the group
    	RWSet rws[] = new CodeBlockRWSet[nextGroup - 1];
    	for(int i = 0; i < nextGroup - 1; i++)
    		rws[i] = new CodeBlockRWSet();
    	Iterator tnIt8 = AllTransactions.iterator();
    	while(tnIt8.hasNext())
    	{
    		Transaction tn = (Transaction) tnIt8.next();
    		Iterator EdgeIt = tn.edges.iterator();
    		while(EdgeIt.hasNext())
    		{
    			DataDependency dd = (DataDependency) EdgeIt.next();
	    		rws[tn.setNumber - 1].union(dd.rw);
    		}
    	}

		// For each transaction group, if all RW Dependencies are POSSIBLY fields of the same object,
		// then check if they are DEFINITELY fields of the same object
		// LIF (Local Info Flow)
		
		
		// If, for all transactions in a group, all RW dependencies are definitely fields of the same object,
		// then that object should be used for synchronization
		

		// Print topological graph in format of graphviz package
		if(optionPrintGraph)
		{
			G.v().out.println("[transaction-graph] strict graph transactions {\n[transaction-graph] start=1;");		
			Iterator tnIt6 = AllTransactions.iterator();
			while(tnIt6.hasNext())
			{
				Transaction tn = (Transaction) tnIt6.next();
				Iterator tnedgeit = tn.edges.iterator();
				G.v().out.println("[transaction-graph] " + tn.name + " [name=\"" + tn.method.toString() + "\"];");
				while(tnedgeit.hasNext())
				{
					DataDependency edge = (DataDependency) tnedgeit.next();
					Transaction tnedge = edge.other;
					G.v().out.println("[transaction-graph] " + tn.name + " -- " + tnedge.name + " [color=" + (edge.size > 5 ? (edge.size > 50 ? "black" : "blue") : "black") + " style=" + (edge.size > 50 ? "dashed" : "solid") + " exactsize=" + edge.size + "];");
				}			
			}
			G.v().out.println("[transaction-graph] }");
		}

		// Print table of transaction information
		if(optionPrintTable)
		{
			G.v().out.println("[transaction-table] ");
			Iterator tnIt7 = AllTransactions.iterator();
			while(tnIt7.hasNext())
			{
				Transaction tn = (Transaction) tnIt7.next();
				G.v().out.println("[transaction-table] Transaction " + tn.name);
				G.v().out.println("[transaction-table] Where: " + tn.method.getDeclaringClass().toString() + ":" + tn.method.toString() + ":  ");
				G.v().out.println("[transaction-table] Prep : " + (tn.prepStmt == null ? "none" : tn.prepStmt.toString()));
				G.v().out.println("[transaction-table] Begin: " + tn.begin.toString());
				G.v().out.print("[transaction-table] End  : " + tn.ends.toString() + " \n");
				G.v().out.println("[transaction-table] Size : " + tn.units.size());
				if(tn.read.size() < 100)
					G.v().out.print("[transaction-table] Read : " + tn.read.size() + "\n[transaction-table] " + 
						tn.read.toString().replaceAll("\\[", "     : [").replaceAll("\n", "\n[transaction-table] ") + 
						(tn.read.size() == 0 ? "\n[transaction-table] " : ""));
				else
					G.v().out.print("[transaction-table] Read : " + tn.read.size() + "  \n[transaction-table] ");
				if(tn.write.size() < 100)
					G.v().out.print("Write: " + tn.write.size() + "\n[transaction-table] " + 
						tn.write.toString().replaceAll("\\[", "     : [").replaceAll("\n", "\n[transaction-table] ") + 
						(tn.write.size() == 0 ? "\n[transaction-table] " : "")); // label provided by previous print statement
				else
					G.v().out.print("Write: " + tn.write.size() + "\n[transaction-table] "); // label provided by previous print statement
				G.v().out.print("Edges: (" + tn.edges.size() + ") "); // label provided by previous print statement
				Iterator tnedgeit = tn.edges.iterator();
				while(tnedgeit.hasNext())
					G.v().out.print(((DataDependency)tnedgeit.next()).other.name + " ");
				G.v().out.println("\n[transaction-table] Group: " + tn.setNumber + "\n[transaction-table] ");
			}
			
			G.v().out.print("[transaction-table] Group Summaries\n[transaction-table] ");
			for(int i = 0; i < nextGroup - 1; i++)
    		{
    			G.v().out.print("Group" + (i + 1) + "\n[transaction-table] " + 
    				rws[i].toString().replaceAll("\\[", "     : [").replaceAll("\n", "\n[transaction-table] ") + 
					(rws[i].size() == 0 ? "\n[transaction-table] " : ""));
	    	}
			G.v().out.println("");
		}

    	// For all methods, run the transformer (Pessimistic Transaction Tranformation)
    	// BEGIN AWFUL HACK
		TransactionBodyTransformer.addedGlobalLockObj = new boolean[nextGroup];
		for(int i = 0; i < nextGroup; i++)
			TransactionBodyTransformer.addedGlobalLockObj[i] = false;
		// END AWFUL HACK
    	Iterator doTransformClassesIt = Scene.v().getApplicationClasses().iterator();
    	while (doTransformClassesIt.hasNext()) 
    	{
    	    SootClass appClass = (SootClass) doTransformClassesIt.next();
    	    Iterator methodsIt = appClass.getMethods().iterator();
    	    while (methodsIt.hasNext())
    	    {
    	    	SootMethod method = (SootMethod) methodsIt.next();
				if(method.isConcrete())
				{
	    	    	Body b = method.getActiveBody();
    	    	
    		    	FlowSet fs = (FlowSet) methodToFlowSet.get(method);
    	    	
   	    			TransactionBodyTransformer.v().setDetails(fs, nextGroup);
   	    			TransactionBodyTransformer.v().internalTransform(b,phaseName, options); 
				}
    	    }
    	}
	}
    
    public boolean mayHappenInParallel(Transaction tn1, Transaction tn2)
    {
    	return mayHappenInParallel(tn1.method, tn2.method);
    }
    
    public boolean mayHappenInParallel(SootMethod m1, SootMethod m2)
    {
    	if(MHPLists == null)
    	{
    		return true;
		}

		int size = MHPLists.size();
		for(int i = 0; i < size; i++)
		{
			if(((List)MHPLists.get(i)).contains(m1))
			{
				for(int j = 0; j < size; j++)
				{
					if(((List)MHPLists.get(j)).contains(m2) && i != j)
					{
						return true;
					}
				}
			}
		}
		return false;
	}
    
    public static boolean contains(String strings[], String string)
    {
    	for(int i = 0; i < strings.length; i++)
    	{
    		if(strings[i].equals(string))
    			return true;
    	}
    	return false;
    }
}
