package assembly;

import java.util.List;

import ast.visitor.AbstractASTVisitor;

import ast.*;
import assembly.instructions.*;
import compiler.Scope;
import compiler.Scope.SymbolTableEntry;

public class CodeGenerator extends AbstractASTVisitor<CodeObject> {

	int intRegCount;
	int floatRegCount;
	static final public String intTempPrefix = "$t";
	static final public String floatTempPrefix = "$f";

	int loopLabel;
	int elseLabel;
	int outLabel;

	// static final public int numIntRegisters = 32;
	// static final public int numFloatRegisters = 32;

	int numIntRegisters;
	int numFloatRegisters;

	String currFunc;

	public CodeGenerator(numRegs) {
		loopLabel = 0;
		elseLabel = 0;
		outLabel = 0;
		intRegCount = 0;
		floatRegCount = 0;
		numIntRegisters = numRegs;
		numFloatRegisters = numRegs;
	}

	// making a helper that branches when false
	private void addFalseBranch(CodeObject result, CondNode node, CodeObject cond, String label) {
		String left = cond.condLeft;
		String right = cond.condRight;

		if (cond.type == Scope.Type.INT) {
			Instruction branch;
			switch (node.getReversedOp()) {
				case LT: branch = new Blt(left, right, label); break;
				case LE: branch = new Ble(left, right, label); break;
				case GT: branch = new Bgt(left, right, label); break;
				case GE: branch = new Bge(left, right, label); break;
				case EQ: branch = new Beq(left, right, label); break;
				case NE: branch = new Bne(left, right, label); break;
				default: throw new Error("unknown comparison!");
			}
			result.code.add(branch);
		} else if (cond.type == Scope.Type.FLOAT) {
			String comparisonResult = generateTemp(Scope.Type.INT);
			Instruction comparison;
			switch (node.getOp()) {
				case EQ:
				case NE: comparison = new Feq(left, right, comparisonResult); break;
				case LT: comparison = new Flt(left, right, comparisonResult); break;
				case LE: comparison = new Fle(left, right, comparisonResult); break;
				case GT: comparison = new Flt(right, left, comparisonResult); break;
				case GE: comparison = new Fle(right, left, comparisonResult); break;
				default: throw new Error("unknown comparison");
			}
			result.code.add(comparison);

			if (node.getOp() == CondNode.OpType.NE) {
				result.code.add(new Bne(comparisonResult, "x0", label));
			} else {
				result.code.add(new Beq(comparisonResult, "x0", label));
			}
		} else {
			throw new Error("unsupported comparison type: " + cond.type);
		}
	}

	public int getIntRegCount() {
		return intRegCount;
	}

	public int getFloatRegCount() {
		return floatRegCount;
	}

	/**
	 * Generate code for Variables
	 *
	 * Create a code object that just holds a variable
	 *
	 * NOTE THAT THIS HAS CHANGED TO GENERATE 3AC INSTEAD
	 */
	@Override
	protected CodeObject postprocess(VarNode node) {

		Scope.SymbolTableEntry sym = node.getSymbol();

		CodeObject co = new CodeObject(sym);
		co.lval = true;
		co.type = node.getType();
		if (sym.isLocal()) {
			co.temp = "$l" + String.valueOf(sym.getAddress());
		} else {
			co.temp = "$g" + sym.getName();
		}


		return co;
	}

	/** Generate code for IntLiterals
	 *
	 * NOTE THAT THIS HAS CHANGED TO GENERATE 3AC INSTEAD
	 */
	@Override
	protected CodeObject postprocess(IntLitNode node) {
		CodeObject co = new CodeObject();

		//Load an immediate into a register
		//The li and la instructions are the same, but it's helpful to distinguish
		//for readability purposes.
		//li tmp' value
		Instruction i = new Li(generateTemp(Scope.Type.INT), node.getVal());

		co.code.add(i); //add this instruction to the code object
		co.lval = false; //co holds an rval -- data
		co.temp = i.getDest(); //temp is in destination of li
		co.type = node.getType();

		return co;
	}

	/** Generate code for FloatLiteras
	 *
	 * NOTE THAT THIS HAS CHANGED TO GENERATE 3AC INSTEAD
	 */
	@Override
	protected CodeObject postprocess(FloatLitNode node) {
		CodeObject co = new CodeObject();

		//Load an immediate into a regisster
		//The li and la instructions are the same, but it's helpful to distinguish
		//for readability purposes.
		//li tmp' value
		Instruction i = new FImm(generateTemp(Scope.Type.FLOAT), node.getVal());

		co.code.add(i); //add this instruction to the code object
		co.lval = false; //co holds an rval -- data
		co.temp = i.getDest(); //temp is in destination of li
		co.type = node.getType();

		return co;
	}

	/**
	 * Generate code for binary operations.
	 *
	 * Step 0: create new code object
	 * Step 1: add code from left child
	 * Step 1a: if left child is an lval, add a load to get the data
	 * Step 2: add code from right child
	 * Step 2a: if right child is an lval, add a load to get the data
	 * Step 3: generate binary operation using temps from left and right
	 *
	 * Don't forget to update the temp and lval fields of the code object!
	 * 	   Hint: where is the result stored? Is this data or an address?
	 *
	 */
	@Override
	protected CodeObject postprocess(BinaryOpNode node, CodeObject left, CodeObject right) {

		CodeObject co = new CodeObject();

		co.code.addAll(left.code);
		co.code.addAll(right.code);
		String dest = generateTemp(node.getType());
		Instruction op;
		boolean isInt = node.getType() == Scope.Type.INT;
		switch (node.getOp()) {
			case ADD: op = isInt ? new Add(left.temp, right.temp, dest) : new FAdd(left.temp, right.temp, dest);
				break;
			case SUB: op = isInt ? new Sub(left.temp, right.temp, dest) : new FSub(left.temp, right.temp, dest);
				break;
			case MUL: op = isInt ? new Mul(left.temp, right.temp, dest) : new FMul(left.temp, right.temp, dest);
				break;
			case DIV: op = isInt ? new Div(left.temp, right.temp, dest) : new FDiv(left.temp, right.temp, dest);
				break;
			default: throw new Error("unknown binary op " + node.getOp());
		}
		co.code.add(op);
		co.temp = dest;
		co.lval = false;
		co.type = node.getType();

		return co;
	}

	/**
	 * Generate code for unary operations.
	 *
	 * Step 0: create new code object
	 * Step 1: add code from child expression
	 * Step 2: generate instruction to perform unary operation
	 *
	 * Don't forget to update the temp and lval fields of the code object!
	 * 	   Hint: where is the result stored? Is this data or an address?
	 *
	 */
	@Override
	protected CodeObject postprocess(UnaryOpNode node, CodeObject expr) {

		CodeObject co = new CodeObject();

		co.code.addAll(expr.code);
		String dest = generateTemp(node.getType());
		Instruction neg;
		switch (node.getType()) {
			case INT: neg = new Neg(expr.temp, dest);
				break;
			case FLOAT: neg = new FNeg(expr.temp, dest);
				break;
			default: throw new Error("can't negate type " + node.getType());
		}
		co.code.add(neg);
		co.temp = dest;
		co.lval = false;
		co.type = node.getType();

		return co;
	}

	/**
	 * Generate code for assignment statements
	 *
	 * Step 0: create new code object
	 * Step 1: if LHS is a variable, generate a load instruction to get the address into a register
	 * Step 1a: add code from LHS of assignment (make sure it results in an lval!)
	 * Step 2: add code from RHS of assignment
	 * Step 2a: if right child is an lval, add a load to get the data
	 * Step 3: generate store
	 *
	 * Hint: it is going to be easiest to just generate a store with a 0 immediate
	 * offset, and the complete store address in a register:
	 *
	 * sw rhs 0(lhs)
	 */
	@Override
	protected CodeObject postprocess(AssignNode node, CodeObject left,
			CodeObject right) {

		CodeObject co = new CodeObject();

		assert(left.isVar());
		co.code.addAll(right.code);
		switch (node.getType()) {
			case INT: co.code.add(new Mv(right.temp, left.temp));
				break;
			case FLOAT: co.code.add(new FMv(right.temp, left.temp));
				break;
			default: throw new Error("can't assign type " + node.getType());
		}
		co.lval = false;
		co.temp = null;
		co.type = null;

		return co;
	}

	/**
	 * Add together all the lists of instructions generated by the children
	 */
	@Override
	protected CodeObject postprocess(StatementListNode node,
			List<CodeObject> statements) {
		CodeObject co = new CodeObject();
		//add the code from each individual statement
		for (CodeObject subcode : statements) {
			co.code.addAll(subcode.code);
		}
		co.type = null; //set to null to trigger errors
		return co;
	}

	/**
	 * Generate code for read
	 *
	 * Step 0: create new code object
	 * Step 1: add code from VarNode (make sure it's an lval)
	 * Step 2: generate GetI instruction, storing into temp
	 * Step 3: generate store, to store temp in variable
	 *
	 * NOTE THAT THIS HAS CHANGED TO GENERATE 3AC INSTEAD
	 */
	@Override
	protected CodeObject postprocess(ReadNode node, CodeObject var) {

		//Step 0
		CodeObject co = new CodeObject();

		//Generating code for read(id)
		assert(var.getSTE() != null); //var had better be a variable

		InstructionList il = new InstructionList();
		switch(node.getType()) {
			case INT:
				//Code to generate if INT:
				//geti var.tmp
				Instruction geti = new GetI(var.temp);
				il.add(geti);
				break;
			case FLOAT:
				//Code to generate if FLOAT:
				//getf var.tmp
				Instruction getf = new GetF(var.temp);
				il.add(getf);
				break;
			default:
				throw new Error("Shouldn't read into other variable");
		}

		co.code.addAll(il);

		co.lval = false; //doesn't matter
		co.temp = null; //set to null to trigger errors
		co.type = null; //set to null to trigger errors

		return co;
	}

	/**
	 * Generate code for print
	 *
	 * Step 0: create new code object
	 *
	 * If printing a string:
	 * Step 1: add code from expression to be printed (make sure it's an lval)
	 * Step 2: generate a PutS instruction printing the result of the expression
	 *
	 * If printing an integer:
	 * Step 1: add code from the expression to be printed
	 * Step 1a: if it's an lval, generate a load to get the data
	 * Step 2: Generate PutI that prints the temporary holding the expression
	 *
	 * NOTE THAT THIS HAS CHANGED TO GENERATE 3AC INSTEAD
	 */
	@Override
	protected CodeObject postprocess(WriteNode node, CodeObject expr) {
		CodeObject co = new CodeObject();

		//generating code for write(expr)

		//for strings, we expect a variable
		if (node.getWriteExpr().getType() == Scope.Type.STRING) {
			//Step 1:
			assert(expr.getSTE() != null);

			//Step 2:
			Instruction write = new PutS(expr.temp);
			co.code.add(write);
		} else {
			//Step 1:
			co.code.addAll(expr.code);

			//Step 2:
			//if type of writenode is int, use puti, if float, use putf
			Instruction write = null;
			switch(node.getWriteExpr().getType()) {
			case STRING: throw new Error("Shouldn't have a STRING here");
			case INT: write = new PutI(expr.temp); break;
			case FLOAT: write = new PutF(expr.temp); break;
			default: throw new Error("WriteNode has a weird type");
			}

			co.code.add(write);
		}

		co.lval = false; //doesn't matter
		co.temp = null; //set to null to trigger errors
		co.type = null; //set to null to trigger errors

		return co;
	}

	/**
	 * FILL IN FROM STEP 3
	 *
	 * Generating an instruction sequence for a conditional expression
	 *
	 * Implement this however you like. One suggestion:
	 *
	 * Create the code for the left and right side of the conditional, but defer
	 * generating the branch until you process IfStatementNode or WhileNode (since you
	 * do not know the labels yet). Modify CodeObject so you can save the necessary
	 * information to generate the branch instruction in IfStatementNode or WhileNode
	 *
	 * Alternate idea 1:
	 *
	 * Don't do anything as part of CodeGenerator. Create a new visitor class
	 * that you invoke *within* your processing of IfStatementNode or WhileNode
	 *
	 * Alternate idea 2:
	 *
	 * Create the branch instruction in this function, then tweak it as necessary in
	 * IfStatementNode or WhileNode
	 *
	 * Hint: you may need to preserve extra information in the returned CodeObject to
	 * make sure you know the type of branch code to generate (int vs float)
	 */
	@Override
	protected CodeObject postprocess(CondNode node, CodeObject left, CodeObject right) {
		CodeObject co = new CodeObject();

		co.code.addAll(left.code);
		co.code.addAll(right.code);
		co.condLeft = left.temp;
		co.condRight = right.temp;
		co.type = left.type;

		return co;
	}

	/**
	 * FILL IN FROM STEP 3
	 *
	 * Step 0: Create code object
	 *
	 * Step 1: generate labels
	 *
	 * Step 2: add code from conditional expression
	 *
	 * Step 3: create branch statement (if not created as part of step 2)
	 * 			don't forget to generate correct branch based on type
	 *
	 * Step 4: generate code
	 * 		<cond code>
	 *		<flipped branch> elseLabel
	 *		<then code>
	 *		j outLabel
	 *		elseLabel:
	 *		<else code>
	 *		outLabel:
	 *
	 * Step 5 insert code into code object in appropriate order.
	 *
	 */
	@Override
	protected CodeObject postprocess(IfStatementNode node, CodeObject cond, CodeObject tlist, CodeObject elist) {
		//Step 0:
		CodeObject co = new CodeObject();

		String out = generateOutLabel();

		co.code.addAll(cond.code);

		if (elist == null) {
			addFalseBranch(co, node.getCondExpr(), cond, out);
			co.code.addAll(tlist.code);
		} else {
			String otherwise = generateElseLabel();
			addFalseBranch(co, node.getCondExpr(), cond, otherwise);
			co.code.addAll(tlist.code);
			co.code.add(new J(out));
			co.code.add(new Label(otherwise));
			co.code.addAll(elist.code);
		}
		co.code.add(new Label(out));

		return co;
	}

		/**
	 * FILL IN FROM STEP 3
	 *
	 * Step 0: Create code object
	 *
	 * Step 1: generate labels
	 *
	 * Step 2: add code from conditional expression
	 *
	 * Step 3: create branch statement (if not created as part of step 2)
	 * 			don't forget to generate correct branch based on type
	 *
	 * Step 4: generate code
	 * 		loopLabel:
	 *		<cond code>
	 *		<flipped branch> outLabel
	 *		<body code>
	 *		j loopLabel
	 *		outLabel:
	 *
	 * Step 5 insert code into code object in appropriate order.
	 */
	@Override
	protected CodeObject postprocess(WhileNode node, CodeObject cond, CodeObject slist) {
		//Step 0:
		CodeObject co = new CodeObject();

		String loop = generateLoopLabel();
		String out = generateOutLabel();

		co.code.add(new Label(loop));
		co.code.addAll(cond.code);
		addFalseBranch(co, node.getCond(), cond, out);
		co.code.addAll(slist.code);
		co.code.add(new J(loop));
		co.code.add(new Label(out));

		return co;
	}

	/**
	 * FILL IN FOR STEP 4
	 *
	 * Generating code for returns
	 *
	 * Step 0: Generate new code object
	 *
	 * Step 1: Add retExpr code to code object (rvalify if necessary)
	 *
	 * Step 2: Store result of retExpr in appropriate place on stack (fp + 8)
	 *
	 * Step 3: Jump to out label (use @link{generateFunctionOutLabel()})
	 */
	@Override
	protected CodeObject postprocess(ReturnNode node, CodeObject retExpr) {
		CodeObject co = new CodeObject();

		co.code.addAll(retExpr.code);

		// $l8 is the return slot at 8(fp)
		switch (node.getFuncSymbol().getReturnType()) {
			case INT: co.code.add(new Mv(retExpr.temp, "$l8"));
				break;
			case FLOAT: co.code.add(new FMv(retExpr.temp, "$l8"));
				break;
			default: throw new Error("bad return type");
		}
		co.code.add(new J(generateFunctionOutLabel()));

		return co;
	}

	@Override
	protected void preprocess(FunctionNode node) {
		// Generate function label information, used for other labels inside function
		currFunc = node.getFuncName();

		//reset register counts; each function uses new registers!
		intRegCount = 0;
		floatRegCount = 0;
	}

	/**
	 * FILL IN FOR STEP 4
	 *
	 * Generate code for functions
	 *
	 * Step 1: add the label for the beginning of the function
	 *
	 * Step 2: manage frame  pointer
	 * 			a. Save old frame pointer
	 * 			b. Move frame pointer to point to base of activation record (current sp)
	 * 			c. Update stack pointer
	 *
	 * Step 3: allocate new stack frame (use scope infromation from FunctionNode)
	 *
	 * Step 4: save registers on stack (Can inspect intRegCount and floatRegCount to know what to save)
	 *
	 * Step 5: add the code from the function body
	 *
	 * Step 6: add post-processing code:
	 * 			a. Label for `return` statements inside function body to jump to
	 * 			b. Restore registers
	 * 			c. Deallocate stack frame (set stack pointer to frame pointer)
	 * 			d. Reset fp to old location
	 * 			e. Return from function
	 */
	@Override
	protected CodeObject postprocess(FunctionNode node, CodeObject body) {
		CodeObject co = new CodeObject();

		co.code.add(new Label(generateFunctionLabel()));
		co.code.add(new Sw("fp", "sp", "0"));
		co.code.add(new Mv("sp", "fp"));
		co.code.add(new Addi("sp", "-4", "sp"));
		// TODO: use local count after spilling temps
		co.code.add(new Addi("sp", String.valueOf(-4 * node.getScope().getNumLocals()), "sp"));
		// TODO: push the physical registers the allocator used (pushRegister)
		// TODO: replace with register-allocated body.code
		co.code.addAll(body.code);
		co.code.add(new Label(generateFunctionOutLabel()));
		// TODO: pop saved registers in reverse order (popRegister)
		co.code.add(new Mv("fp", "sp"));
		co.code.add(new Lw("fp", "fp", "0"));
		co.code.add(new Ret());

		/** ADD REGISTER ALLOCATION HERE
		 *
		 * You may find it useful to do this in the following way:
		 *
		 * 1. Write a register allocator class that is initialized with the number of int/fp registers to use, the code from
		 * 		`body`, and the function scope from `node` (the function scope gives you access to local/global variables)
		 * 2. Within the register allocator class, do the following
		 * 		a. Split the code in body into basic blocks
		 * 		b. (573 version) Perform liveness analysis on each basic block (assume globals and locals are live)
		 * 		b. (468/595 version) Assume all locals/globals/temporaries are live all the time
		 * 		c. Perform register allocation on each basic block using the algorithms presented in class,
		 * 			converting 3AC into assembly code with macro expansion
		 * 			i. Add code to track the state of the registers for each basic block (what is assigned to the register, whether it's dirty)
		 * 			ii. As you perform register allocation within a basic block, spill registers to memory as necessary. Use any
		 * 				heuristic you want to determine which registers to allocate and which to spill
		 * 			iii. If you need to spill a temporary to memory, you'll find it easiest to add the temporary as a new "local" variable
		 * 				to the local scope (you can just use the temporary name as the variable name); that will automatically allocate a spot
		 * 				in the activation record for it.
		 * 			iv. At the end of each basic block, save all dirty/live registers that hold globals/locals back to the stack
		 * 3. Once register allocation is done, track:
		 * 		a. How big the local scope is after spilling temporaries -- this affects allocating the stack frame
		 * 		b. How many total registers you used -- this affects the register save/restore code
		 * 4. Now generate code for your function as before, but using the updated information for register save/restore and frame allocation
		 */


		return co;
	}

	/**
	 * Generate code for the list of functions. This is the "top level" code generation function
	 *
	 * Step 1: Set fp to point to sp
	 *
	 * Step 2: Insert a JR to main
	 *
	 * Step 3: Insert a HALT
	 *
	 * Step 4: Include all the code of the functions
	 */
	@Override
	protected CodeObject postprocess(FunctionListNode node, List<CodeObject> funcs) {
		CodeObject co = new CodeObject();

		co.code.add(new Mv("sp", "fp"));
		co.code.add(new Jr(generateFunctionLabel("main")));
		co.code.add(new Halt());
		co.code.add(new Blank());

		//add code for each of the functions
		for (CodeObject c : funcs) {
			co.code.addAll(c.code);
			co.code.add(new Blank());
		}

		return co;
	}

	/**
	*
	* FILL IN FOR STEP 4
	*
	* Generate code for a call expression
	 *
	 * Step 1: For each argument:
	 *
	 * 	Step 1a: insert code of argument (don't forget to rvalify!)
	 *
	 * 	Step 1b: push result of argument onto stack
	 *
	 * Step 2: alloate space for return value
	 *
	 * Step 3: push current return address onto stack
	 *
	 * Step 4: jump to function
	 *
	 * Step 5: pop return address back from stack
	 *
	 * Step 6: pop return value into fresh temporary (destination of call expression)
	 *
	 * Step 7: remove arguments from stack (move sp)
	 */
	@Override
	protected CodeObject postprocess(CallNode node, List<CodeObject> args) {

		//STEP 0
		CodeObject co = new CodeObject();

		for (CodeObject arg : args) {
			co.code.addAll(arg.code);
			co.code.add(arg.type == Scope.Type.FLOAT ? new PushFloat(arg.temp) : new PushInt(arg.temp));
		}
		co.code.add(new Addi("sp", "-4", "sp"));
		co.code.add(new PushInt("ra"));
		co.code.add(new Jr(generateFunctionLabel(node.getFuncName())));
		co.code.add(new PopInt("ra"));
		String ret = generateTemp(node.getType());
		co.code.add(node.getType() == Scope.Type.FLOAT ? new PopFloat(ret) : new PopInt(ret));
		co.code.add(new Addi("sp", String.valueOf(4 * args.size()), "sp"));

		co.temp = ret;
		co.lval = false;
		co.type = node.getType();

		return co;
	}

	/**
	 * Generate a fresh temporary
	 *
	 * @return new temporary register name
	 */
	protected String generateTemp(Scope.Type t) {
		switch(t) {
			case INT: return intTempPrefix + String.valueOf(++intRegCount);
			case FLOAT: return floatTempPrefix + String.valueOf(++floatRegCount);
			default: throw new Error("Generating temp for bad type");
		}
	}

	protected String generateLoopLabel() {
		return "loop_" + String.valueOf(++loopLabel);
	}

	protected String generateElseLabel() {
		return  "else_" + String.valueOf(++elseLabel);
	}

	protected String generateOutLabel() {
		return "out_" +  String.valueOf(++outLabel);
	}

	protected String generateFunctionLabel() {
		return "func_" + currFunc;
	}

	protected String generateFunctionLabel(String func) {
		return "func_" + func;
	}

	protected String generateFunctionOutLabel() {
		return "func_ret_" + currFunc;
	}

	/**
	 * Take a code object that results in an lval, and create a new code
	 * object that adds a load to generate the rval.
	 *
	 * @param lco The code object resulting in an address
	 * @return A code object with all the code of <code>lco</code> followed by a load
	 *         to generate an rval
	 */
	protected CodeObject rvalify(CodeObject lco) {

		assert (lco.lval == true);
		CodeObject co = new CodeObject();

		/* THIS WON'T BE NECESSARY IF YOU'RE GENERATING 3AC */

		/* DON'T FORGET TO ADD CODE TO GENERATE LOADS FOR LOCAL VARIABLES */

		return co;
	}

	/**
	 * Generate an instruction sequence that holds the address of the variable in a code object
	 *
	 * If it's a global variable, just get the address from the symbol table
	 *
	 * If it's a local variable, compute the address relative to the frame pointer (fp)
	 *
	 * @param lco The code object holding a variable
	 * @return a list of instructions that puts the address of the variable in a register
	 */
	private InstructionList generateAddrFromVariable(CodeObject lco) {

		InstructionList il = new InstructionList();

		//Step 1:
		SymbolTableEntry symbol = lco.getSTE();
		String address = symbol.addressToString();

		//Step 2:
		Instruction compAddr = null;
		if (symbol.isLocal()) {
			//If local, address is offset
			//need to load fp + offset
			//addi tmp' fp offset
			compAddr = new Addi("fp", address, generateTemp(Scope.Type.INT));
		} else {
			//If global, address in symbol table is the right location
			//la tmp' addr //Register type needs to be an int
			compAddr = new La(generateTemp(Scope.Type.INT), address);
		}
		il.add(compAddr); //add instruction to code object

		return il;
	}

	private void pushRegister(InstructionList il, String reg, Scope.Type t) {
		il.add(t == Scope.Type.FLOAT ? new Fsw(reg, "sp", "0") : new Sw(reg, "sp", "0"));
		il.add(new Addi("sp", "-4", "sp"));
	}

	private void popRegister(InstructionList il, String reg, Scope.Type t) {
		il.add(new Addi("sp", "4", "sp"));
		il.add(t == Scope.Type.FLOAT ? new Flw(reg, "sp", "0") : new Lw(reg, "sp", "0"));
	}

}
