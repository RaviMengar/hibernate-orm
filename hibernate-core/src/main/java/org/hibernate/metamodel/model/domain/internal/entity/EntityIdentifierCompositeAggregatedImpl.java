/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.metamodel.model.domain.internal.entity;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.persistence.TemporalType;

import org.hibernate.HibernateException;
import org.hibernate.boot.model.domain.ValueMapping;
import org.hibernate.engine.internal.UnsavedValueFactory;
import org.hibernate.engine.spi.IdentifierValue;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;
import org.hibernate.internal.util.ReflectHelper;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.RootClass;
import org.hibernate.metamodel.model.creation.spi.RuntimeModelCreationContext;
import org.hibernate.metamodel.model.domain.NavigableRole;
import org.hibernate.metamodel.model.domain.RepresentationMode;
import org.hibernate.metamodel.model.domain.spi.AbstractSingularPersistentAttribute;
import org.hibernate.metamodel.model.domain.spi.AllowableParameterType;
import org.hibernate.metamodel.model.domain.spi.EmbeddedTypeDescriptor;
import org.hibernate.metamodel.model.domain.spi.EntityIdentifierCompositeAggregated;
import org.hibernate.metamodel.model.domain.spi.ManagedTypeDescriptor;
import org.hibernate.metamodel.model.domain.spi.Navigable;
import org.hibernate.metamodel.model.domain.spi.NavigableVisitationStrategy;
import org.hibernate.metamodel.model.domain.spi.SimpleTypeDescriptor;
import org.hibernate.metamodel.model.domain.spi.SingularPersistentAttribute;
import org.hibernate.metamodel.model.relational.spi.Column;
import org.hibernate.procedure.ParameterMisuseException;
import org.hibernate.query.NavigablePath;
import org.hibernate.query.sqm.produce.spi.SqmCreationState;
import org.hibernate.query.sqm.tree.domain.SqmEmbeddedValuedSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.domain.SqmNavigableReference;
import org.hibernate.sql.SqlExpressableType;
import org.hibernate.sql.ast.Clause;
import org.hibernate.sql.ast.produce.metamodel.spi.Fetchable;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.type.descriptor.java.spi.EmbeddableJavaDescriptor;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * @author Steve Ebersole
 */
public class EntityIdentifierCompositeAggregatedImpl<O,J>
		extends AbstractSingularPersistentAttribute<O,J>
		implements EntityIdentifierCompositeAggregated<O,J> {
	private final EmbeddedTypeDescriptor<J> embeddedDescriptor;
	private final IdentifierGenerator identifierGenerator;
	private final List<Column> columns;
	private final IdentifierValue unsavedValue;


	@SuppressWarnings("unchecked")
	public EntityIdentifierCompositeAggregatedImpl(
			EntityHierarchyImpl runtimeModelHierarchy,
			RootClass bootModelRootEntity,
			EmbeddedTypeDescriptor embeddedDescriptor,
			RuntimeModelCreationContext creationContext) {
		super(
				runtimeModelHierarchy.getRootEntityType(),
				bootModelRootEntity.getIdentifierProperty(),
				embeddedDescriptor.getRepresentationStrategy().generatePropertyAccess(
						bootModelRootEntity,
						bootModelRootEntity.getIdentifierProperty(),
						(ManagedTypeDescriptor<?>) embeddedDescriptor.getContainer(),
						creationContext.getSessionFactory().getSessionFactoryOptions().getBytecodeProvider()
				),
				Disposition.ID
		);

		this.embeddedDescriptor = embeddedDescriptor;
		this.identifierGenerator = creationContext.getSessionFactory().getIdentifierGenerator( bootModelRootEntity.getEntityName() );

		final ValueMapping<?> valueMapping = bootModelRootEntity.getIdentifierAttributeMapping().getValueMapping();
		this.columns = valueMapping.getMappedColumns().stream()
				.map( creationContext.getDatabaseObjectResolver()::resolveColumn )
				.collect( Collectors.toList() );

		unsavedValue = UnsavedValueFactory.getUnsavedIdentifierValue(
				bootModelRootEntity.getIdentifier().getNullValue(),
				getPropertyAccess().getGetter(),
				embeddedDescriptor.getJavaTypeDescriptor(),
				getConstructor( bootModelRootEntity )
		);
	}

	@Override
	public EmbeddedTypeDescriptor<J> getEmbeddedDescriptor() {
		return embeddedDescriptor;
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// NavigableContainer (embedded)

	@Override
	public NavigableRole getNavigableRole() {
		return getEmbeddedDescriptor().getNavigableRole();
	}

	@Override
	public EmbeddableJavaDescriptor<J> getJavaTypeDescriptor() {
		return getEmbeddedDescriptor().getJavaTypeDescriptor();
	}

	@Override
	public int getNumberOfJdbcParametersForRestriction() {
		return getEmbeddedDescriptor().getNumberOfJdbcParametersForRestriction();
	}

	@Override
	public void visitFetchables(Consumer<Fetchable> fetchableConsumer) {
		getEmbeddedDescriptor().visitFetchables( fetchableConsumer );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// SingularAttribute


	@Override
	public PersistentAttributeType getPersistentAttributeType() {
		return PersistentAttributeType.EMBEDDED;
	}

	@Override
	public PersistenceType getPersistenceType() {
		return PersistenceType.EMBEDDABLE;
	}

	@Override
	public SingularAttributeClassification getAttributeTypeClassification() {
		return SingularAttributeClassification.EMBEDDED;
	}

	@Override
	public String asLoggableText() {
		return "IdentifierCompositeAggregated(" + embeddedDescriptor.asLoggableText() + ")";
	}

	@Override
	public SimpleTypeDescriptor<J> getNavigableType() {
		return getEmbeddedDescriptor();
	}

	@Override
	public boolean hasSingleIdAttribute() {
		return true;
	}

	@Override
	@SuppressWarnings("unchecked")
	public SingularPersistentAttribute asAttribute(Class javaType) {
		return this;
	}

	@Override
	public IdentifierGenerator getIdentifierValueGenerator() {
		return identifierGenerator;
	}

	@Override
	public List<Column> getColumns() {
		return columns;
	}

	@Override
	public <N> Navigable<N> findNavigable(String navigableName) {
		return getEmbeddedDescriptor().findNavigable( navigableName );
	}

	@Override
	public void visitNavigables(NavigableVisitationStrategy visitor) {
		getEmbeddedDescriptor().visitNavigables( visitor );
	}

	@Override
	public boolean isOptional() {
		return false;
	}

	@Override
	public int getNumberOfJdbcParametersNeeded() {
		return getColumns().size();
	}

	@Override
	public boolean canContainSubGraphs() {
		return false;
	}

	@Override
	public IdentifierValue getUnsavedValue() {
		return unsavedValue;
	}

	@Override
	public AllowableParameterType resolveTemporalPrecision(TemporalType temporalType, TypeConfiguration typeConfiguration) {
		throw new ParameterMisuseException( "Cannot apply temporal precision to embeddable value" );
	}

	@Override
	public Object resolveHydratedState(
			Object hydratedForm,
			ExecutionContext executionContext,
			SharedSessionContractImplementor session,
			Object containerInstance) {
		if ( hydratedForm == null ) {
			return null;
		}
		else {
			Object[] hydratedState = (Object[]) hydratedForm;
			Object[] state = new Object[getEmbeddedDescriptor().getStateArrayContributors().size()];

			getEmbeddedDescriptor().visitStateArrayContributors(
					contributor -> {
						final int index = contributor.getStateArrayPosition();
						state[index] = contributor.resolveHydratedState(
								hydratedState[index],
								executionContext,
								session,
								containerInstance
						);
					}
			);

			Object result = getEmbeddedDescriptor().instantiate( session );
			getEmbeddedDescriptor().setPropertyValues( result, state );

			return result;
		}
	}

	@Override
	public Object unresolve(Object value, SharedSessionContractImplementor session) {
		final Object[] values = getEmbeddedDescriptor().getPropertyValues( value );
		getEmbeddedDescriptor().visitStateArrayContributors(
				contributor -> {
					final int index = contributor.getStateArrayPosition();
					values[index] = contributor.unresolve( values[index], session );
				}
		);

		return values;
	}

	@Override
	public void dehydrate(
			Object value,
			JdbcValueCollector jdbcValueCollector,
			Clause clause,
			SharedSessionContractImplementor session) {
		final Object[] values = (Object[]) value;
		final AtomicInteger position = new AtomicInteger();
		getEmbeddedDescriptor().visitStateArrayContributors(
				contributor -> contributor.dehydrate(
						values[position.getAndIncrement()],
						jdbcValueCollector,
						clause,
						session
				)
		);
	}

	@Override
	public void visitColumns(
			BiConsumer<SqlExpressableType, Column> action,
			Clause clause,
			TypeConfiguration typeConfiguration) {
		getColumns().forEach( column -> action.accept( column.getExpressableType(), column ) );
	}

	@Override
	public SqmNavigableReference createSqmExpression(SqmPath lhs, SqmCreationState creationState) {
		final NavigablePath navigablePath = lhs.getNavigablePath().append( getNavigableName() );
		return new SqmEmbeddedValuedSimplePath(
				navigablePath,
				this,
				lhs
		);
	}

	@Override
	public EmbeddedTypeDescriptor<J> getType() {
		return getAttributeType();
	}

	@Override
	public EmbeddedTypeDescriptor<J> getAttributeType() {
		return getEmbeddedDescriptor();
	}

	@Override
	public EmbeddedTypeDescriptor<?> getValueGraphType() {
		return getAttributeType();
	}

	@Override
	public SimpleTypeDescriptor<?> getKeyGraphType() {
		return null;
	}

	private static Constructor getConstructor(PersistentClass persistentClass) {
		if ( persistentClass == null || persistentClass.getExplicitRepresentationMode() != RepresentationMode.POJO ) {
			return null;
		}

		try {
			return ReflectHelper.getDefaultConstructor( persistentClass.getMappedClass() );
		}
		catch (Throwable t) {
			return null;
		}
	}

	@Override
	public boolean areEqual(J x, J y) throws HibernateException {
		return getEmbeddedDescriptor().areEqual( x,y );
	}

	@Override
	public int extractHashCode(J o) {
		return getEmbeddedDescriptor().extractHashCode( o );
	}
}