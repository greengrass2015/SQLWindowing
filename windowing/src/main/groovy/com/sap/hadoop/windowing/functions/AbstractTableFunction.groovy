package com.sap.hadoop.windowing.functions

import groovy.lang.GroovyShell;

import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;

import com.sap.hadoop.windowing.WindowingException;
import com.sap.hadoop.windowing.functions.annotations.FunctionDef;
import com.sap.hadoop.windowing.query.Column;
import com.sap.hadoop.windowing.query.FuncSpec;
import com.sap.hadoop.windowing.query.Query;
import com.sap.hadoop.windowing.query.TypeUtils;
import com.sap.hadoop.windowing.query.Window;
import com.sap.hadoop.windowing.runtime.IPartition;
import com.sap.hadoop.windowing.runtime.IPartitionIterator;

/**
 * A Table Function instance is associated with information on how input data should be partitioned and ordered.
 * Each ordered partition is provided to an instance of this Function,  instance is responsible for outputting 
 * a {@link IPartition} given an input Partition. 
 * <p>
 * A Function may also specify that it wants to operate on(map) the raw input data (before it is partitioned and ordered).
 * The Function is free to reshape the data in any way, hence it is responsible for providing the Output Shape 
 * (as a name -> TypeInfo map). If the function operates on raw data, again it is free to alter it in any fashion; 
 * but it is responsible for providing the Output Shape of the Map operation.
 * 
 * @author harish.butani
 *
 */
abstract class AbstractTableFunction implements IPartitionIterator
{
	IPartitionIterator input;
	Window window
	
	/*
	 * set by execution driving logic ( see {@link Reduce}
	 * Only the first function in the chain doesn't have its map logic applied. 
	 */
	boolean isFirstFunctionInChain = false;
	
	AbstractTableFunction()
	{
	}
	
	boolean hasNext()
	{
		return input.hasNext();
	}
	
	IPartition next()
	{
		try
		{
			IPartition p = input.next()
			if ( !isFirstFunctionInChain && hasMapPhase() )
			{
				p = mapExecute(p)
			}
			return execute(p);
		}
		catch(WindowingException we)
		{
			throw new RuntimeException(we);
		}
	}
	
	void remove() { input.remove(); }
	
	protected void completeTranslation(GroovyShell wshell, Query qry, FuncSpec funcSpec) throws WindowingException
	{
	}
	
	abstract protected IPartition execute(IPartition inpPart) throws WindowingException;
	
	/**
	 * callback used by translation mechanics to introspect on function's output.
	 * @return
	 */
	abstract Map<String, TypeInfo> getOutputShape();
	
	public boolean hasMapPhase()  throws WindowingException { return FunctionRegistry.hasMapPhase(getClass()); }
	
	protected IPartition mapExecute(IPartition inpPart) throws WindowingException
	{
		throw new WindowingException("Function's Map Execution not implemented)");
	}
	
	/**
	 * callback used by translation mechanics to introspect on function's output at Map Stage.
	 * @return
	 */
	public Map<String, TypeInfo> getMapPhaseOutputShape()
	{
		throw new WindowingException("Function's 'getMapPhaseOutputShape' method not implemented)");
	}
	
	/*
	 * A TblFunction that reshapes the Input, must also provide a SerDe.
	 * Why cannot we assume a specific SerDe for all TblFuncs?
	 * This is an optimization: by working directly with the Writables in the OutputPartition we only need to extract the 
	 * partition/sort columns to be assembled into a CompositeWritable. This also implies the Value part of the Map record doesn't
	 * need to go through any transformations (Writable -> Java -> Writable), the Writables from the output partition can be 
	 * directly given to the Output Collector. 
	 */
	public SerDe getMapOutputPartitionSerDe()
	{
		throw new WindowingException("Function's 'getMapOutputPartitionSerDe' method not implemented)");
	}
	
	protected Map<String, TypeInfo> getInputTypeMap(GroovyShell wshell, Query qry, FuncSpec funcSpec) throws WindowingException
	{
		Map<String, TypeInfo> typemap
		if ( input == null || ! (input instanceof AbstractTableFunction))
		{
			typemap = [:]
			qry.input.columns.each { Column c ->
				typemap[c.field.fieldName] = TypeInfoUtils.getTypeInfoFromObjectInspector(c.field.fieldObjectInspector)
			}
		}
		else
		{
			typemap = ((AbstractTableFunction)input).getOutputShape();
		}
		return typemap
	}
}

@FunctionDef(
	name = "noop",
	supportsWindow = true,
	args = [],
	description = "test function"
)
class Noop extends AbstractTableFunction
{
	Map<String, TypeInfo> typemap;
	
	protected IPartition execute(IPartition inpPart) throws WindowingException
	{
		return inpPart;
	}
	
	Map<String, TypeInfo> getOutputShape()
	{
		return typemap;
	}
	
	protected void completeTranslation(GroovyShell wshell, Query qry, FuncSpec funcSpec) throws WindowingException
	{
		if ( input == null || ! (input instanceof AbstractTableFunction))
		{
			typemap = [:]
			qry.input.columns.each { Column c ->
				typemap[c.field.fieldName] = TypeInfoUtils.getTypeInfoFromObjectInspector(c.field.fieldObjectInspector)
			}
		}
		else
		{
			typemap = ((AbstractTableFunction)input).getOutputShape();
		}
	}
}

@FunctionDef(
	name = "noopwithmap",
	supportsWindow = true,
	args = [],
	description = "test function",
	hasMapPhase = true
)
class NoopWithMap extends Noop
{
	SerDe mapOutputSerDe;
	
	protected IPartition mapExecute(IPartition inpPart) throws WindowingException
	{
		return inpPart;
	}
	
	public Map<String, TypeInfo> getMapPhaseOutputShape()
	{
		return typemap;
	}
	
	protected void completeTranslation(GroovyShell wshell, Query qry, FuncSpec funcSpec) throws WindowingException
	{
		super.completeTranslation(wshell, qry, funcSpec)
		//mapOutputSerDe = TypeUtils.createLazyBinarySerDe(qry.cfg, typemap)
		
		
		if ( input == null || ! (input instanceof AbstractTableFunction))
		{
			mapOutputSerDe = qry.input.deserializer
		}
		else
		{
			AbstractTableFunction iFunc = (AbstractTableFunction)input
			if (iFunc.hasMapPhase())
			{
				mapOutputSerDe = iFunc.getMapOutputPartitionSerDe();
			}
			else
			{
				/*
				* since the execute call simply returns the input Partition in this case the
				* SerDe returned here is different from the SerDe returned in the Partition.
				*/
				mapOutputSerDe = TypeUtils.createLazyBinarySerDe(qry.cfg, typemap)
			}
		}
		
	}
	
	public SerDe getMapOutputPartitionSerDe()
	{
		return mapOutputSerDe
	}
}