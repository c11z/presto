/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.ResolvedFunction;
import io.prestosql.metadata.Signature;
import io.prestosql.spi.function.ScalarFunction;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.function.TypeParameter;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarcharType;
import io.prestosql.sql.planner.FunctionCallBuilder;
import io.prestosql.sql.planner.plan.DynamicFilterId;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.StringLiteral;
import io.prestosql.sql.tree.SymbolReference;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.type.StandardTypes.BOOLEAN;
import static io.prestosql.spi.type.StandardTypes.VARCHAR;
import static io.prestosql.sql.ExpressionUtils.extractConjuncts;
import static java.util.Objects.requireNonNull;

public final class DynamicFilters
{
    private DynamicFilters() {}

    public static Expression createDynamicFilterExpression(Metadata metadata, DynamicFilterId id, Type inputType, SymbolReference input)
    {
        return createDynamicFilterExpression(metadata, id, inputType, (Expression) input);
    }

    @VisibleForTesting
    public static Expression createDynamicFilterExpression(Metadata metadata, DynamicFilterId id, Type inputType, Expression input)
    {
        return new FunctionCallBuilder(metadata)
                .setName(QualifiedName.of(Function.NAME))
                .addArgument(VarcharType.VARCHAR, new StringLiteral(id.toString()))
                .addArgument(inputType, input)
                .build();
    }

    public static ExtractResult extractDynamicFilters(Expression expression)
    {
        List<Expression> conjuncts = extractConjuncts(expression);

        ImmutableList.Builder<Expression> staticConjuncts = ImmutableList.builder();
        ImmutableList.Builder<Descriptor> dynamicConjuncts = ImmutableList.builder();

        for (Expression conjunct : conjuncts) {
            Optional<Descriptor> descriptor = getDescriptor(conjunct);
            if (descriptor.isPresent()) {
                dynamicConjuncts.add(descriptor.get());
            }
            else {
                staticConjuncts.add(conjunct);
            }
        }

        return new ExtractResult(staticConjuncts.build(), dynamicConjuncts.build());
    }

    public static boolean isDynamicFilter(Expression expression)
    {
        return getDescriptor(expression).isPresent();
    }

    public static Optional<Descriptor> getDescriptor(Expression expression)
    {
        if (!(expression instanceof FunctionCall)) {
            return Optional.empty();
        }

        FunctionCall functionCall = (FunctionCall) expression;
        boolean isDynamicFilterFunction = ResolvedFunction.fromQualifiedName(functionCall.getName())
                .map(ResolvedFunction::getSignature)
                .map(Signature::getName)
                .map(Function.NAME::equals)
                .orElse(false);
        if (!isDynamicFilterFunction) {
            return Optional.empty();
        }

        List<Expression> arguments = functionCall.getArguments();
        checkArgument(arguments.size() == 2, "invalid arguments count: %s", arguments.size());

        Expression firstArgument = arguments.get(0);
        checkArgument(firstArgument instanceof StringLiteral, "firstArgument is expected to be an instance of StringLiteral: %s", firstArgument.getClass().getSimpleName());
        String id = ((StringLiteral) firstArgument).getValue();
        return Optional.of(new Descriptor(new DynamicFilterId(id), arguments.get(1)));
    }

    public static class ExtractResult
    {
        private final List<Expression> staticConjuncts;
        private final List<Descriptor> dynamicConjuncts;

        public ExtractResult(List<Expression> staticConjuncts, List<Descriptor> dynamicConjuncts)
        {
            this.staticConjuncts = ImmutableList.copyOf(requireNonNull(staticConjuncts, "staticConjuncts is null"));
            this.dynamicConjuncts = ImmutableList.copyOf(requireNonNull(dynamicConjuncts, "dynamicConjuncts is null"));
        }

        public List<Expression> getStaticConjuncts()
        {
            return staticConjuncts;
        }

        public List<Descriptor> getDynamicConjuncts()
        {
            return dynamicConjuncts;
        }
    }

    public static final class Descriptor
    {
        private final DynamicFilterId id;
        private final Expression input;

        public Descriptor(DynamicFilterId id, Expression input)
        {
            this.id = requireNonNull(id, "id is null");
            this.input = requireNonNull(input, "input is null");
        }

        public DynamicFilterId getId()
        {
            return id;
        }

        public Expression getInput()
        {
            return input;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Descriptor that = (Descriptor) o;
            return Objects.equals(id, that.id) &&
                    Objects.equals(input, that.input);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id, input);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("id", id)
                    .add("input", input)
                    .toString();
        }
    }

    @ScalarFunction(value = Function.NAME, hidden = true)
    public static final class Function
    {
        private Function() {}

        private static final String NAME = "$internal$dynamic_filter_function";

        @TypeParameter("T")
        @SqlType(BOOLEAN)
        public static boolean dynamicFilter(@SqlType(VARCHAR) Slice id, @SqlType("T") Object input)
        {
            throw new UnsupportedOperationException();
        }

        @TypeParameter("T")
        @SqlType(BOOLEAN)
        public static boolean dynamicFilter(@SqlType(VARCHAR) Slice id, @SqlType("T") long input)
        {
            throw new UnsupportedOperationException();
        }

        @TypeParameter("T")
        @SqlType(BOOLEAN)
        public static boolean dynamicFilter(@SqlType(VARCHAR) Slice id, @SqlType("T") boolean input)
        {
            throw new UnsupportedOperationException();
        }

        @TypeParameter("T")
        @SqlType(BOOLEAN)
        public static boolean dynamicFilter(@SqlType(VARCHAR) Slice id, @SqlType("T") double input)
        {
            throw new UnsupportedOperationException();
        }
    }
}
