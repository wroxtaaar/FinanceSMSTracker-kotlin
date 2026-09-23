package com.example.financesmstracker.parser

interface SmsParser {
    fun parse(sender: String, messageBody: String): ParserResult?
}
