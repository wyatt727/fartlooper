package com.wobbz.fartloop

import com.wobbz.fartloop.core.network.RuleEvaluator
import com.wobbz.fartloop.feature.rules.model.RealRuleEvaluator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing application-level dependencies.
 *
 * CIRCULAR DEPENDENCY RESOLUTION: RuleEvaluator interface moved to core:network
 * This eliminates the circular dependency between app and feature:rules modules.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    /**
     * Provide RuleEvaluator implementation.
     * Updated to use real rule evaluator now that Team B has implemented the visual rule builder.
     *
     * INTERFACE LOCATION: RuleEvaluator interface now in core:network module
     * Implementation remains in feature:rules for clean separation of concerns.
     */
    @Binds
    @Singleton
    abstract fun bindRuleEvaluator(impl: RealRuleEvaluator): RuleEvaluator
}
