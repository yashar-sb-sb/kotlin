/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.componentArrayAccessor
import org.jetbrains.kotlin.fir.declarations.FirCallableMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.classId
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.symbols.StandardClassIds
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassTypeImpl

interface FirSamResolver : FirSessionComponent {
    fun resolveFunctionTypeIfSamInterface(firRegularClass: FirRegularClass): ConeKotlinType?
    fun getFunctionTypeForPossibleSamType(type: ConeKotlinType): ConeKotlinType?
    fun shouldRunSamConversionForFunction(firNamedFunction: FirNamedFunction): Boolean
}

val FirSession.samResolver: FirSamResolver by componentArrayAccessor()

class FirSamResolverImpl(private val firSession: FirSession) : FirSamResolver {
    override fun resolveFunctionTypeIfSamInterface(firRegularClass: FirRegularClass): ConeKotlinType? {
        val abstractMethod = firRegularClass.getSingleAbstractMethodOrNull(firSession) ?: return null
        // TODO: val shouldConvertFirstParameterToDescriptor = samWithReceiverResolvers.any { it.shouldConvertFirstSamParameterToReceiver(abstractMethod) }

        return abstractMethod.getFunctionTypeForAbstractMethod(firSession)
    }

    override fun getFunctionTypeForPossibleSamType(type: ConeKotlinType): ConeKotlinType? {
        return when (type) {
            is ConeClassType -> getFunctionTypeForPossibleSamType(type)
            is ConeFlexibleType -> ConeFlexibleType(
                getFunctionTypeForPossibleSamType(type.lowerBound) ?: return null,
                getFunctionTypeForPossibleSamType(type.upperBound) ?: return null
            )
            is ConeClassErrorType -> null
            // TODO: support those types as well
            is ConeAbbreviatedType, is ConeTypeParameterType, is ConeTypeVariableType,
            is ConeCapturedType, is ConeDefinitelyNotNullType, is ConeIntersectionType -> null
        }
    }

    private fun getFunctionTypeForPossibleSamType(type: ConeClassType): ConeLookupTagBasedType? {
        val firRegularClass =
            firSession.firSymbolProvider.getClassLikeSymbolByFqName(type.lookupTag.classId)?.fir as? FirRegularClass ?: return null

        val unsubstitutedFunctionType = resolveFunctionTypeIfSamInterface(firRegularClass) ?: return null
        val substitutor =
            substitutorByMap(
                firRegularClass.typeParameters
                    .map { it.symbol }
                    .zip(type.typeArguments.map {
                        (it as? ConeTypedProjection)?.type
                            ?: ConeClassTypeImpl(ConeClassLikeLookupTagImpl(StandardClassIds.Any), emptyArray(), isNullable = true)
                    })
                    .toMap()
            )

        val result =
            substitutor
                .substituteOrSelf(unsubstitutedFunctionType)
                .withNullability(ConeNullability.create(type.isMarkedNullable))

        require(result is ConeLookupTagBasedType) {
            "Function type should always be ConeLookupTagBasedType, but ${result::class} was found"
        }

        return result
    }

    override fun shouldRunSamConversionForFunction(firNamedFunction: FirNamedFunction): Boolean {
        // TODO: properly support, see org.jetbrains.kotlin.load.java.sam.JvmSamConversionTransformer.shouldRunSamConversionForFunction
        return true
    }
}

private fun FirRegularClass.getSingleAbstractMethodOrNull(session: FirSession): FirNamedFunction? {
    // TODO: add optimizations from org.jetbrains.kotlin.load.java.lazy.descriptors.LazyJavaClassDescriptor.isDefinitelyNotSamInterface

    // TODO: restrict to Java interfaces
    if (classKind != ClassKind.INTERFACE) return null

    // TODO: support more complicated abstract methods detection (including ones from supertypes) through use-site scope
    if (superTypeRefs.isNotEmpty() && superTypeRefs.singleOrNull()?.coneTypeSafe<ConeClassType>()?.isAny() != true) return null


    val abstractMethod =
        declarations.singleOrNull { (it as? FirCallableMemberDeclaration<*>)?.modality == Modality.ABSTRACT }
            as? FirNamedFunction
            ?: return null

    var enhancedMethod: FirNamedFunction? = null

    session.firSymbolProvider.getClassUseSiteMemberScope(classId, session, ScopeSession())
        ?.processFunctionsByName(abstractMethod.name) { functionSymbol ->
            val firFunction = functionSymbol.fir
            require(firFunction is FirNamedFunction) {
                "${functionSymbol.callableId.callableName} is expected to be FirNamedFunction, but ${functionSymbol::class} was found"
            }

            if (firFunction.modality != Modality.ABSTRACT) return@processFunctionsByName ProcessorAction.NEXT

            require(enhancedMethod == null) {
                "More than one abstract method was found for ${firFunction.name} in $classId"
            }

            enhancedMethod = firFunction
            ProcessorAction.STOP
        }

    require(enhancedMethod != null) {
        "Abstract method was not found for ${abstractMethod.name} in $classId"
    }

    if (enhancedMethod!!.typeParameters.isNotEmpty()) return null

    return enhancedMethod
}

private fun ConeClassType.isAny() = lookupTag.classId == StandardClassIds.Any

private fun FirNamedFunction.getFunctionTypeForAbstractMethod(session: FirSession): ConeLookupTagBasedType {
    val parameterTypes = valueParameters.map {
        it.returnTypeRef.coneTypeSafe<ConeKotlinType>() ?: ConeKotlinErrorType("No type for parameter $it")
    }

    return createFunctionalType(
        session, parameterTypes,
        receiverType = null,
        rawReturnType = returnTypeRef.coneTypeSafe() ?: ConeKotlinErrorType("No type for return type of $this")
    )
}
