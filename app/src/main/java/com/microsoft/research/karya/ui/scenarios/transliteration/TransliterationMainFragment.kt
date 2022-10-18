package com.microsoft.research.karya.ui.scenarios.transliteration

import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.microsoft.research.karya.R
import com.microsoft.research.karya.databinding.ItemFloatWordBinding
import com.microsoft.research.karya.databinding.MicrotaskTransliterationBinding
import com.microsoft.research.karya.ui.scenarios.common.BaseMTRendererFragment
import com.microsoft.research.karya.ui.scenarios.transliteration.TransliterationViewModel.WordVerificationStatus
import com.microsoft.research.karya.ui.scenarios.transliteration.validator.Validator
import com.microsoft.research.karya.utils.extensions.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TransliterationMainFragment :
    BaseMTRendererFragment(R.layout.microtask_transliteration) {
    override val viewModel: TransliterationViewModel by viewModels()
    val args: TransliterationMainFragmentArgs by navArgs()

    private val binding by viewBinding(MicrotaskTransliterationBinding::bind)

    private var prevInvalidWord: String = ""

    override fun requiredPermissions(): Array<String> {
        return emptyArray()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        // TODO: Remove this once we have viewModel Factory
        viewModel.setupViewModel(args.taskId, args.completed, args.total)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()

        with(binding) {
            sentenceEt.inputType =
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            sentenceEt.filters = arrayOf(
                InputFilter { source, start, end, dest, dstart, dend ->
                    return@InputFilter source.replace(Regex("[^a-z]*"), "")
                }
            )

            /** record instruction */
            /** record instruction */
            val recordInstruction =
                viewModel.task.params.asJsonObject.get("instruction").asString ?: ""
            instructionTv.text = recordInstruction

            addBtn.setOnClickListener { addWord() }

            sentenceEt.onSubmit { addWord() }

            nextBtn.setOnClickListener { onNextClick() }
        }

        Validator.init()
    }

    private fun addWord() = with(binding) {

        errorTv.gone() // Remove any existing errors

        val word = sentenceEt.text.toString()
        val outputVariants = viewModel.outputVariants.value!!
        val inputVariants = viewModel.inputVariants.value!!

        if (word.contains(" ")) {
            showError("Only 1 word allowed")
            return
        }

        if (word.isEmpty()) {
            showError("Please enter a word")
            return
        }

        if (outputVariants.containsKey(word) || inputVariants.containsKey(word)) {
            showError("The word is already present")
            return
        }

        if (inputVariants.values.count { wordDetail ->
            wordDetail.verificationStatus == WordVerificationStatus.NEW
        } == viewModel.limit
        ) {
            showError("Only upto ${viewModel.limit} words are allowed.")
            return
        }

        if (viewModel.mlFeedback &&
            !Validator.isValid(viewModel.sourceLanguage, viewModel.sourceWord, word) &&
            word != prevInvalidWord
        ) {
            prevInvalidWord = word
            showError(
                "This transliteration doesn't seem right. Please check it and " +
                    "press add again if you think its correct"
            )
            return
        }

        viewModel.addWord(word)
    }

    private fun showError(error: String) = with(binding) {
        errorTv.text = error
        errorTv.visible()
    }

    private fun onNextClick() {
        if (!viewModel.allowValidation && viewModel.inputVariants.value!!.size == 0) {
            showError("Please enter atleast one word")
            return
        } else if (viewModel.allowValidation) {
            var atleastOneValidNew = viewModel.inputVariants.value!!.isNotEmpty()
            var noUnknowns = true
            for ((word, wordDetail) in viewModel.outputVariants.value!!) {
                when (wordDetail.verificationStatus) {
                    WordVerificationStatus.UNKNOWN -> {
                        noUnknowns = false
                        break
                    }
                    WordVerificationStatus.VALID -> {
                        atleastOneValidNew = true
                    }
                    else -> {}
                }
            }
            if (!noUnknowns) {
                showError("Please validate all variants")
                return
            }
            if (!atleastOneValidNew) {
                showError("Please enter at least one valid or new variant")
                return
            }
        }
        with(binding) {
            errorTv.gone()
            userVariantLayout.removeAllViews()
        }
        viewModel.handleNextClick()
    }

    private fun setupObservers() = with(binding) {
        viewModel.wordTvText.observe(
            viewLifecycleOwner.lifecycle,
            viewLifecycleScope
        ) { text -> contextTv.text = text }

        viewModel.outputVariants.observe(viewLifecycleOwner) { variants ->

            verifyFlowLayout.removeAllViews()

            for ((word, wordDetail) in variants) {
                val itemBinding = ItemFloatWordBinding.inflate(layoutInflater)
                with(itemBinding) {
                    sentence.text = word

                    when (wordDetail.verificationStatus) {
                        WordVerificationStatus.VALID -> setValidUI(itemBinding)
                        WordVerificationStatus.INVALID -> setInvaidUI(itemBinding)
                        WordVerificationStatus.UNKNOWN -> setUnknownUI(itemBinding)
                        else -> {}
                    }

                    root.setOnClickListener {

                        // If validation is not allowed return
                        if (!viewModel.allowValidation) return@setOnClickListener

                        when (wordDetail.verificationStatus) {
                            WordVerificationStatus.VALID -> viewModel.modifyStatus(
                                word,
                                WordVerificationStatus.INVALID
                            )
                            WordVerificationStatus.INVALID, WordVerificationStatus.UNKNOWN -> viewModel.modifyStatus(
                                word,
                                WordVerificationStatus.VALID
                            )
                            else -> {}
                        }
                    }
                    verifyFlowLayout.addView(root)
                }
            }
        }

        viewModel.inputVariants.observe(viewLifecycleOwner) { variants ->
            userVariantLayout.removeAllViews()
            for (word in variants.keys.reversed()) {
                val itemBinding = ItemFloatWordBinding.inflate(layoutInflater)
                with(itemBinding) {
                    sentence.text = word
                    setNewUI(this)
                    removeImageView.setOnClickListener { viewModel.removeWord(word) }
                    userVariantLayout.addView(root)
                }
            }
            // Clear the edittext
            sentenceEt.setText("")
        }
    }

    private fun setValidUI(itemBinding: ItemFloatWordBinding) = with(itemBinding) {
        floatSentenceCard.background.setTint(
            ContextCompat.getColor(
                requireContext(),
                R.color.c_light_green
            )
        )
        removeImageView.gone()
    }

    private fun setInvaidUI(itemBinding: ItemFloatWordBinding) = with(itemBinding) {
        floatSentenceCard.background.setTint(
            ContextCompat.getColor(
                requireContext(),
                R.color.c_red
            )
        )
        removeImageView.gone()
    }

    private fun setNewUI(itemBinding: ItemFloatWordBinding) = with(itemBinding) {
        floatSentenceCard.background.setTint(
            ContextCompat.getColor(
                requireContext(),
                R.color.c_light_grey
            )
        )
        removeImageView.visible()
    }

    private fun setUnknownUI(itemBinding: ItemFloatWordBinding) = with(itemBinding) {
        floatSentenceCard.background.setTint(Color.LTGRAY)
        removeImageView.gone()
    }

    fun EditText.onSubmit(func: () -> Unit) {
        setOnEditorActionListener { _, actionId, _ ->

            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                func()
            }
            true
        }
    }
}
